package com.example.collaborativeworkspace.common.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Shared checksum primitive; digests do not grant authorization. */
public final class ContentDigest {
    private ContentDigest() {}
    public static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
}
