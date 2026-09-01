package com.graphvault.artwork.datafetcher;

import com.graphvault.asset.grpc.ArtworkUrlRequest;
import com.graphvault.asset.grpc.ArtworkUrlResponse;
import com.graphvault.asset.grpc.AssetServiceGrpc;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import net.devh.boot.grpc.client.inject.GrpcClient;

import java.util.List;

/**
 * This class is the whole point of artwork-dgs existing: it answers a GraphQL query by making
 * a gRPC call, translating between the two protocols the blog says Netflix uses at different
 * layers of the stack.
 *
 * {@code @GrpcClient("asset-service")} (from grpc-client-spring-boot-starter) injects a fully
 * configured stub — connection pooling, the channel, retry/deadline plumbing — pointed at
 * whatever address the "asset-service" name resolves to in application.yml. Nothing here
 * constructs a {@code ManagedChannel} by hand.
 *
 * We use the BLOCKING stub deliberately, not the async one. That's a direct callback to the
 * blog's RxJava section: on older Netflix code, mixing a blocking web layer with reactive
 * downstream calls meant juggling two concurrency models at once. With virtual threads (this
 * service's Tomcat runs on them too — see application.yml), a blocking gRPC call from a virtual
 * thread just... blocks that one lightweight thread, cheaply, while the platform thread
 * underneath goes and does other work. No callbacks, no `Mono`/`Flux`, no thread-pool sizing
 * exercise.
 */
@DgsComponent
public class ArtworkDataFetcher {

    @GrpcClient("asset-service")
    private AssetServiceGrpc.AssetServiceBlockingStub assetServiceStub;

    @DgsQuery
    public List<ArtworkType> artworkForTitle(@InputArgument Long titleId) {
        ArtworkUrlRequest request = ArtworkUrlRequest.newBuilder()
                .setTitleId(titleId)
                .build();

        // A plain synchronous method call that happens to cross a process boundary over the
        // network -- this is the entire "developer experience" win virtual threads are meant
        // to deliver: it reads exactly like calling a local method, with none of the reactive
        // machinery a non-blocking equivalent would need.
        ArtworkUrlResponse response = assetServiceStub.getArtworkUrls(request);

        return response.getAssetsList().stream()
                .map(asset -> new ArtworkType(asset.getType(), asset.getSignedUrl()))
                .toList();
    }
}
