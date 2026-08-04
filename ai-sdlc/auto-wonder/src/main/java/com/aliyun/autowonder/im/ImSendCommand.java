package com.aliyun.autowonder.im;

public record ImSendCommand(
        String provider,
        String externalUserId,
        String title,
        String markdown) {
}
