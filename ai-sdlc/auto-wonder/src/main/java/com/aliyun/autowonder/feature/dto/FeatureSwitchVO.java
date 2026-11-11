package com.aliyun.autowonder.feature.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FeatureSwitchVO {
    /** 重复工单意图检测；缺行等价 false。 */
    private boolean duplicateWorkitemIntentEnabled;
    private int version;
    /** 自助注册审核；缺行等价 false。 */
    private boolean userRegistrationReviewEnabled;
    private int userRegistrationReviewVersion;
    /** 默认允许用户创建空间；缺行等价 true。 */
    private boolean workspaceCreationDefaultAllowed = true;
    private int workspaceCreationDefaultAllowedVersion;
}
