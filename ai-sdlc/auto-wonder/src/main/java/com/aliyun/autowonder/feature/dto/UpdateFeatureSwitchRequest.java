package com.aliyun.autowonder.feature.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateFeatureSwitchRequest {
    private Boolean duplicateWorkitemIntentEnabled;
    /** 读取时的版本；首次写入传 0 或省略。 */
    private Integer expectedVersion;

    // 两项新增开关各自独立字段与版本：同时提交时互不覆盖，未提交的开关保持原值。
    private Boolean userRegistrationReviewEnabled;
    private Integer userRegistrationReviewVersion;
    private Boolean workspaceCreationDefaultAllowed;
    private Integer workspaceCreationDefaultAllowedVersion;
}
