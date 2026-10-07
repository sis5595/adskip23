package org.adskip.core;

/** Transport boundary. Implementations receive only exact BVID+CID and client metadata. */
public interface AnalysisRequestDataSource {
    AnalysisRequestResult submit(BiliVideoKey videoKey, String client, String clientVersion);
}
