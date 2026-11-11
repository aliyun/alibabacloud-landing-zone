package com.aliyun.autowonder.workitem.duplicate;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DuplicateCheckRequest {
    private String title;
    /** 编辑场景排除自身；新建或草稿不传。 */
    private Long excludeWorkitemId;
    /** 前端表单会话标识，用于同一表单内请求去重。 */
    private String formRequestId;
    private Integer page;
    private Integer size;
}
