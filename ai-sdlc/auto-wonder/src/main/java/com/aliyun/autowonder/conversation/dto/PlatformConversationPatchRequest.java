package com.aliyun.autowonder.conversation.dto;

import lombok.Data;

@Data
public class PlatformConversationPatchRequest {
    /** 非空即改名，改名后标题来源固定为 USER，不会再被自动标题覆盖。 */
    private String title;
    /** true 归档，false 取消归档，null 表示本次不动归档状态。 */
    private Boolean archived;
}
