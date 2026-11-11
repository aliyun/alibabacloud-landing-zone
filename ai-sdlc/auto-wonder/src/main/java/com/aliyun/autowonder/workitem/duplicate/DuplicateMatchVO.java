package com.aliyun.autowonder.workitem.duplicate;

import lombok.Getter;
import lombok.Setter;

/** 一条重复命中：标题同名或同意图。 */
@Getter
@Setter
public class DuplicateMatchVO {
    private Long id;
    private String title;
    private String statusName;
    private String statusCategory;
    /** 可信完整地址；无法生成时为 null，调用方回退到 id。 */
    private String url;
    /** EXACT_TITLE / SAME_INTENT */
    private String matchType;
}
