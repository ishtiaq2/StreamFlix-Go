package com.graphvault.asset;

import com.graphvault.asset.grpc.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.Instant;

/**
 * The actual gRPC handler. {@code AssetServiceGrpc.AssetServiceImplBase} is 100% generated code
 * (from asset.proto, via the protobuf-maven-plugin build step) — we only extend it and fill in
 * the one RPC method the .proto file declared.
 *
 * {@code @GrpcService} (from grpc-server-spring-boot-starter, NOT a Spring-native annotation)
 * is what tells the starter "register this bean as a handler on the gRPC server", the gRPC
 * equivalent of {@code @RestController} for HTTP.
 */
@GrpcService
public class AssetGrpcService extends AssetServiceGrpc.AssetServiceImplBase {

    @Override
    public void getArtworkUrls(ArtworkUrlRequest request, StreamObserver<ArtworkUrlResponse> responseObserver) {
        long titleId = request.getTitleId();
        long fakeExpiry = Instant.now().plusSeconds(900).getEpochSecond();

        // Real Netflix asset delivery resolves device capability, region, and DRM licensing
        // before minting a genuinely signed, short-lived CDN URL. Here we just fabricate three
        // plausible-looking URLs so artwork-dgs has something real to merge into a GraphQL
        // response — the whole point of this service is demonstrating the gRPC *call*, not
        // real asset delivery.
        ArtworkUrlResponse response = ArtworkUrlResponse.newBuilder()
                .addAssets(fakeAsset(titleId, "BOX_ART", fakeExpiry))
                .addAssets(fakeAsset(titleId, "BACKGROUND", fakeExpiry))
                .addAssets(fakeAsset(titleId, "LOGO", fakeExpiry))
                .build();

        // gRPC's StreamObserver pattern: onNext() delivers the response, onCompleted() closes
        // the call. For this unary (non-streaming) RPC that's always exactly one onNext() call,
        // but the same interface is what makes server-streaming and bidi-streaming RPCs
        // possible elsewhere in the framework without a different API shape.
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private ArtworkAsset fakeAsset(long titleId, String type, long expiry) {
        return ArtworkAsset.newBuilder()
                .setType(type)
                .setSignedUrl("https://cdn.graphvault.example/art/%d/%s.jpg?sig=fake&exp=%d"
                        .formatted(titleId, type.toLowerCase(), expiry))
                .build();
    }
}
