package com.aliyun.autowonder.category.dto;

import lombok.Getter;
import lombok.Setter;

/** 批量打标的逐项结果：失败不中断整批，调用方可按失败项幂等重试。 */
@Getter
@Setter
public class BatchSkillCategoryResultVO {
    private Long skillId;
    private boolean success;
    private String message;
}
