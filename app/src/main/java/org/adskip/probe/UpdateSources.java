package org.adskip.probe;

/** Public, reviewed source configuration. Never put credentials here. */
final class UpdateSources {
    static final String PROJECT = "https://github.com/sis5595/adskip23";
    static final String RELEASES = PROJECT + "/releases";
    static final String METADATA_PRIMARY = "https://api.github.com/repos/sis5595/adskip23/releases?per_page=20";
    // Direct metadata fallback is optional; configure only after a trustworthy mirror is available.
    static final String METADATA_FALLBACK = "";
    static final String APK_PRIMARY_PREFIX = PROJECT + "/releases/download/";
    // Optional direct APK mirror comes from reviewed release metadata; Lanzou is a browser page.
    static final String APK_MIRROR_PAGE = "https://wwapq.lanzoub.com/b01n4igtqj";
    static final String[] DOWNLOAD_HOSTS = {"api.github.com", "github.com",
            "release-assets.githubusercontent.com", "objects.githubusercontent.com"};
    static final String CERTIFICATE = "92a317090b0f24ec56af22852eb6dfba9dcffbbe878d7a16c90820e045de8a03";
    private UpdateSources() { }
}
