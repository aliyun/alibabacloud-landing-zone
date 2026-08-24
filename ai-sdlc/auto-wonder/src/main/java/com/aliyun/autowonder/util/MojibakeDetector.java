package com.aliyun.autowonder.util;

/**
 * Detects text that was decoded with the wrong charset (typically GBK bytes
 * decoded as UTF-8 on Chinese Windows executors), producing U+FFFD runs or
 * Latin-1/Extended-Latin garbage.
 */
public final class MojibakeDetector {

    private MojibakeDetector() {
    }

    public static boolean looksLikeMojibake(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.indexOf('\uFFFD') >= 0) {
            return true;
        }
        int suspicious = 0;
        int visible = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) {
                visible++;
            }
            if ((ch >= '\u00C0' && ch <= '\u024F') || ch == '\u00A0') {
                suspicious++;
            }
        }
        return suspicious >= 3 && suspicious * 2 >= Math.max(1, visible);
    }
}
