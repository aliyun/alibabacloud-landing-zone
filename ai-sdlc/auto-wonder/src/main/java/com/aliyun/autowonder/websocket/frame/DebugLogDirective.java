package com.aliyun.autowonder.websocket.frame;

import lombok.Getter;
import lombok.Setter;

/**
 * TASK_DISPATCH 帧 debugLog 段（协议契约，与 runtime 计划逐字一致）：
 * {"enabled": true, "maxBytes": 209715200}。maxBytes 为单轮日志硬上限，缺省 200MB。
 */
@Getter
@Setter
public class DebugLogDirective {
    private Boolean enabled;
    private Long maxBytes;
}
