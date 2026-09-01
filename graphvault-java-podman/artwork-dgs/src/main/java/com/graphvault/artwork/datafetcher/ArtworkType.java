package com.graphvault.artwork.datafetcher;

/** The GraphQL-facing shape, deliberately much smaller than the gRPC ArtworkAsset message it's
 * built from — GraphQL callers see exactly two fields; the internal gRPC contract could grow a
 * dozen more (cache headers, DRM tokens, whatever) without this schema ever needing to change. */
public record ArtworkType(String type, String url) {}
