package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import java.util.regex.Pattern;

/** External group identifiers are matched exactly after trimming, preserving case. */
public final class ImGroupIds {
    private static final Pattern DINGTALK = Pattern.compile("[A-Za-z0-9_+=/:.,$@-]{1,256}");
    private static final Pattern FEISHU = Pattern.compile("oc_[A-Za-z0-9]{1,100}");

    private ImGroupIds() {
    }

    public static String normalize(String provider, String groupId) {
        String value = groupId == null ? null : groupId.trim();
        boolean valid = value != null && !value.isEmpty() && value.length() <= 256;
        if (valid && "DINGTALK".equals(provider)) {
            valid = DINGTALK.matcher(value).matches();
        }
        if (valid && "FEISHU".equals(provider)) {
            valid = FEISHU.matcher(value).matches();
        }
        if (!valid) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "integration.group_id_invalid",
                    new Object[0], "群 ID 格式不正确");
        }
        return value;
    }
}
