package com.aliyun.autowonder.debuglog;

import lombok.Getter;
import lombok.Setter;

/** 签发请求体（协议契约）：{"sizeBytes":123,"sha256":"&lt;64hex&gt;","truncated":false,"dispatchStatus":"SUCCEEDED"} */
@Getter
@Setter
public class DebugLogUploadRequest {
    private Long sizeBytes;
    private String sha256;
    private Boolean truncated;
    private String dispatchStatus;
}
