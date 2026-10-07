package org.adskip.core;

import java.util.Objects;

/**
 * Explains how (or whether) a CID was established for a BVID.
 *
 * <p>This is intentionally not a nullable CID.  A rule can reach automatic selection only when
 * the evidence is {@link Kind#EXACT_SINGLE_PAGE}.  {@code EXACT_EXTERNAL} exists to reserve the
 * future contract, but no external resolver is trusted or implemented in this build.</p>
 */
public final class CidEvidence {
    public enum Kind {
        EXACT_SINGLE_PAGE,
        EXACT_EXTERNAL,
        COMMON_ACROSS_CANDIDATES,
        UNKNOWN_MULTI,
        UNKNOWN
    }

    private final Kind kind;
    private final String bvid;
    private final BiliVideoKey exactVideo;

    private CidEvidence(Kind kind, String bvid, BiliVideoKey exactVideo) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.bvid = bvid;
        this.exactVideo = exactVideo;
    }

    public static CidEvidence exactSinglePage(BiliVideoKey video) {
        return exact(Kind.EXACT_SINGLE_PAGE, video);
    }

    /** Reserved for a future independently verified external CID resolver. Not executable yet. */
    public static CidEvidence exactExternalReserved(BiliVideoKey video) {
        return exact(Kind.EXACT_EXTERNAL, video);
    }

    public static CidEvidence commonAcrossCandidates(String bvid) {
        return new CidEvidence(Kind.COMMON_ACROSS_CANDIDATES, requireBvid(bvid), null);
    }

    public static CidEvidence unknownMulti(String bvid) {
        return new CidEvidence(Kind.UNKNOWN_MULTI, requireBvid(bvid), null);
    }

    public static CidEvidence unknown() {
        return new CidEvidence(Kind.UNKNOWN, null, null);
    }

    private static CidEvidence exact(Kind kind, BiliVideoKey video) {
        Objects.requireNonNull(video, "video");
        return new CidEvidence(kind, video.getBvid(), video);
    }

    public Kind getKind() {
        return kind;
    }

    public String getBvid() {
        return bvid;
    }

    public boolean hasExactVideo() {
        return exactVideo != null;
    }

    public BiliVideoKey getExactVideo() {
        if (exactVideo == null) {
            throw new IllegalStateException("CID evidence has no exact BVID + CID");
        }
        return exactVideo;
    }

    /** The only currently implemented evidence authorized to publish automatic rules. */
    public boolean permitsAutomaticRulePublishing() {
        return kind == Kind.EXACT_SINGLE_PAGE && exactVideo != null;
    }

    public boolean matches(BiliVideoKey video) {
        return exactVideo != null && exactVideo.equals(video);
    }

    private static String requireBvid(String value) {
        if (value == null || value.trim().isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("bvid must be nonblank and unpadded");
        }
        // Decoding validates both the canonical shape and its checksum-like reversible encoding.
        BiliIdCodec.bvidToAid(value);
        return value;
    }
}
