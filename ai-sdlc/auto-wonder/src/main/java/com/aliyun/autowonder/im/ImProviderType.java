package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import java.util.Locale;

public enum ImProviderType {
    DINGTALK("DINGTALK");

    private final String key;

    ImProviderType(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public static String normalize(String provider) {
        String normalized = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "IM provider 不能为空");
        }
        return normalized;
    }
}
