package com.aliyun.autowonder.integration.receipt;

import java.util.Arrays;
import java.util.stream.Collectors;

public final class ExternalOperationKeys {

    private ExternalOperationKeys() {
    }

    public static String aoneComment(long sourceWorkitemId, long commentId) {
        return semantic("aone.comment", sourceWorkitemId, commentId);
    }

    public static String marker(String operationKey) {
        return "<!-- aw-op:" + ExternalOperationDigests.sha256(operationKey).substring(0, 24) + " -->";
    }

    private static String semantic(String prefix, Object... parts) {
        String material = Arrays.stream(parts)
                .map(value -> value == null ? "" : String.valueOf(value).trim())
                .collect(Collectors.joining("\u0000"));
        return prefix + ":" + ExternalOperationDigests.sha256(material);
    }
}
