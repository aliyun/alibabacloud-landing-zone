package com.aliyun.autowonder.dispatch;

/** A freshly signed, short-lived download link for one restorable checkpoint. */
public record CheckpointDownloadLink(
        long checkpointSeq,
        String downloadUrl,
        String expiresAt,
        String sha256,
        int expiresInSeconds) {
}
