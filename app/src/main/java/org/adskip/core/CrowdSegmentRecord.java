package org.adskip.core;

import java.util.Objects;

/** Raw lexical values supplied by a transport; only {@link CrowdSegmentParser} may turn it into a rule. */
public final class CrowdSegmentRecord {
    private final String bvid;
    private final String cid;
    private final String uuid;
    private final String category;
    private final String action;
    private final String startSeconds;
    private final String endSeconds;
    private final String durationSeconds;

    public CrowdSegmentRecord(String bvid, String cid, String uuid, String category, String action,
            String startSeconds, String endSeconds, String durationSeconds) {
        this.bvid = bvid;
        this.cid = cid;
        this.uuid = uuid;
        this.category = category;
        this.action = action;
        this.startSeconds = startSeconds;
        this.endSeconds = endSeconds;
        this.durationSeconds = durationSeconds;
    }

    public String getBvid() { return bvid; }
    public String getCid() { return cid; }
    public String getUuid() { return uuid; }
    public String getCategory() { return category; }
    public String getAction() { return action; }
    public String getStartSeconds() { return startSeconds; }
    public String getEndSeconds() { return endSeconds; }
    public String getDurationSeconds() { return durationSeconds; }
}
