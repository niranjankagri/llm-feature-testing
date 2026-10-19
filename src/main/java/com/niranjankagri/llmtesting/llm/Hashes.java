package com.niranjankagri.llmtesting.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 as hex, for recording keys and the prompt fingerprint in reports. */
public final class Hashes {

    /** Static helper only: no instances. */
    private Hashes() {
    }

    /** SHA-256 of the UTF-8 bytes as lower-case hex. */
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
