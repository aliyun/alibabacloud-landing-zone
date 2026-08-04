package com.aliyun.autowonder.util;

import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicInteger;

public class ApplicationUtil {
    public static final String DEFAULT_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss,SSS";
    public static final String DEFAULT_TIME_ZONE = "Asia/Shanghai";

    public static DateTimeFormatter FORMATTER = DateTimeFormat.forPattern(DEFAULT_TIME_FORMAT).withZone(DateTimeZone.forID(DEFAULT_TIME_ZONE));

    private static AtomicInteger nextInc = new AtomicInteger((new java.util.Random()).nextInt());

    public static String getSingletonId(Long userId) {
        if (userId == null) {
            userId = (long) 1344371;
        }
        return Long.toHexString(userId) + Integer.toHexString((int) System.currentTimeMillis() / 1000)
                + Integer.toHexString(nextInc.getAndIncrement());
    }

    public static boolean isShuttingDown() {
        URL resource = Thread.currentThread().getContextClassLoader().getResource("META-INF/resources/status.taobao");
        return resource == null;
    }

    public static String generateHashId(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes());
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
