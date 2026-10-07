package org.adskip.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** One-way digest helper so diagnostic timelines need not retain raw titles or surface values. */
public final class EvidenceFingerprint {
    private EvidenceFingerprint() {
    }

    public static String sha256(String value) {
        if (value == null) {
            throw new IllegalArgumentException("fingerprint input must not be null");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(64);
            for (byte item : digest) {
                output.append(String.format("%02x", item & 0xff));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
