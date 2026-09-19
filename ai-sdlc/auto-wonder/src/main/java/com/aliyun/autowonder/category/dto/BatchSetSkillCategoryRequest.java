package com.aliyun.autowonder.category.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BatchSetSkillCategoryRequest {
    private List<Long> skillIds;
    /** null 表示批量取消打标；字段缺省视为非法请求，由控制器校验。 */
    private Long categoryId;
}
