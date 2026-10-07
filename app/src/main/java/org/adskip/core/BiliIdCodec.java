package org.adskip.core;

/** Lossless conversion between Bilibili's numeric aid and canonical 12-character BV id. */
public final class BiliIdCodec {
    public static final long MAX_AID = (1L << 51) - 1L;

    private static final long HIGH_BIT = 1L << 51;
    private static final long XOR_CODE = 23_442_827_791_579L;
    private static final int BASE = 58;
    private static final String ALPHABET =
            "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf";

    private BiliIdCodec() {
    }

    public static String aidToBvid(long aid) {
        requireValidAid(aid);
        char[] output = "BV1000000000".toCharArray();
        long encoded = (HIGH_BIT | aid) ^ XOR_CODE;
        int outputIndex = output.length - 1;
        while (encoded > 0L && outputIndex >= 3) {
            output[outputIndex--] = ALPHABET.charAt((int) (encoded % BASE));
            encoded /= BASE;
        }
        swap(output, 3, 9);
        swap(output, 4, 7);
        return new String(output);
    }

    public static long bvidToAid(String bvid) {
        if (bvid == null || bvid.length() != 12 || !bvid.startsWith("BV1")) {
            throw new IllegalArgumentException("BVID must be the canonical 12-character BV1 form");
        }
        char[] input = bvid.toCharArray();
        swap(input, 3, 9);
        swap(input, 4, 7);

        long encoded = 0L;
        for (int index = 3; index < input.length; index++) {
            int digit = ALPHABET.indexOf(input[index]);
            if (digit < 0) {
                throw new IllegalArgumentException("BVID contains a character outside the base58 alphabet");
            }
            encoded = Math.addExact(Math.multiplyExact(encoded, BASE), digit);
        }
        long aid = (encoded & MAX_AID) ^ XOR_CODE;
        requireValidAid(aid);
        if (!aidToBvid(aid).equals(bvid)) {
            throw new IllegalArgumentException("BVID is not canonical");
        }
        return aid;
    }

    private static void requireValidAid(long aid) {
        if (aid <= 0L || aid > MAX_AID) {
            throw new IllegalArgumentException("aid must be in 1..2^51-1");
        }
    }

    private static void swap(char[] value, int left, int right) {
        char temporary = value[left];
        value[left] = value[right];
        value[right] = temporary;
    }
}
