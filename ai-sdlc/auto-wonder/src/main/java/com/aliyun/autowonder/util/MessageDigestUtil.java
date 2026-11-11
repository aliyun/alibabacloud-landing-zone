package com.aliyun.autowonder.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MessageDigestUtil {

    public static String getMD5(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(data);
        byte[] digestBytes = md.digest();
        return bytesToHex(digestBytes);
    }

    /** Lowercase hex SHA-256 of UTF-8 bytes; null hashes as the empty string. */
    public static String sha256Hex(byte[] data) {
        try {
            return bytesToHex(MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(String value) {
        return sha256Hex((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
        }
        return result.toString();
    }
}
