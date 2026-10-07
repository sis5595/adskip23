import org.adskip.core.UpdatePolicy;

public final class UpdatePolicySelfTest {
    private static int checks;
    private static final String HASH = "a".repeat(64), CERT = "b".repeat(64);
    public static void main(String[] args) {
        check(UpdatePolicy.due(100, 0));
        check(!UpdatePolicy.due(1000, 999));
        check(!UpdatePolicy.due(1000 + UpdatePolicy.CHECK_INTERVAL_MS - 1, 1000));
        check(UpdatePolicy.due(1000 + UpdatePolicy.CHECK_INTERVAL_MS, 1000));
        check(UpdatePolicy.due(999, 1000));
        check(UpdatePolicy.allowedUrl("https://github.com/a?x=1", new String[]{"github.com"}));
        for (String bad : new String[]{"http://github.com/a", "https://github.com.evil.test/a", "https://evil.test/github.com",
                "https://user@github.com/a", "https://github.com:444/a", "file:///tmp/a", "https://github.com/a#fragment", "//github.com/a"}) {
            check(!UpdatePolicy.allowedUrl(bad, new String[]{"github.com"}));
        }
        verify("com.sis5595.adskip23", 50, HASH, CERT, CERT);
        reject(() -> verify("org.adskip.probe", 50, HASH, CERT, CERT));
        reject(() -> verify("com.sis5595.adskip23", 49, HASH, CERT, CERT));
        reject(() -> verify("com.sis5595.adskip23", 48, HASH, CERT, CERT));
        reject(() -> verify("com.sis5595.adskip23", 51, HASH, CERT, CERT));
        reject(() -> verify("com.sis5595.adskip23", 50, "c".repeat(64), CERT, CERT));
        reject(() -> verify("com.sis5595.adskip23", 50, HASH, "d".repeat(64), CERT));
        reject(() -> verify("com.sis5595.adskip23", 50, HASH, CERT, "e".repeat(64)));
        for (String bad : new String[]{"", "a".repeat(63), "g".repeat(64), "a".repeat(65)}) check(!UpdatePolicy.validHash(bad));
        System.out.println("UpdatePolicySelfTest: " + checks + " checks passed");
    }
    private static void verify(String pkg, long code, String actualHash, String currentCert, String downloadedCert) {
        UpdatePolicy.validateArtifact("com.sis5595.adskip23", 49, 50, HASH, actualHash, pkg, code,
                currentCert, downloadedCert, CERT);
        checks++;
    }
    private static void reject(Runnable action) {
        try { action.run(); throw new AssertionError("unsafe update accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError(); }
}
