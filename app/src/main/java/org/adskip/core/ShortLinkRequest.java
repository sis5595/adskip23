package org.adskip.core;

import java.net.URI;
import java.util.Optional;

/**
 * Ephemeral research request for exactly one official Bilibili short link.
 *
 * <p>The URL is intentionally available only to the transport. Callers must retain the digest,
 * not the URL, in diagnostics or audit output.</p>
 */
public final class ShortLinkRequest {
    private final EvidenceScope scope;
    private final URI url;
    private final String inputFingerprint;

    private ShortLinkRequest(EvidenceScope scope, URI url, String inputFingerprint) {
        this.scope = scope;
        this.url = url;
        this.inputFingerprint = inputFingerprint;
    }

    public static Optional<ShortLinkRequest> fromSharedText(
            String input, EvidenceScope scope) {
        if (scope == null) return Optional.empty();
        Optional<ShareTransportRequest> request = ShareTransportRequest.fromSharedText(input);
        return request.map(value -> new ShortLinkRequest(scope,
                value.getUrlForTransportOnly(), value.getInputFingerprint()));
    }

    public EvidenceScope getScope() { return scope; }
    public URI getUrlForTransportOnly() { return url; }
    public String getInputFingerprint() { return inputFingerprint; }
}
