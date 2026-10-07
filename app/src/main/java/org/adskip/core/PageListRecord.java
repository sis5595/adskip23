package org.adskip.core;

/** Lexical record emitted by a pagelist transport before research candidate validation. */
public final class PageListRecord {
    private final String cid;
    private final long page;
    private final long durationSeconds;
    private final String part;

    public PageListRecord(String cid, long page, long durationSeconds, String part) {
        this.cid = cid;
        this.page = page;
        this.durationSeconds = durationSeconds;
        this.part = part;
    }

    public String getCid() { return cid; }
    public long getPage() { return page; }
    public long getDurationSeconds() { return durationSeconds; }
    public String getPart() { return part; }
}
