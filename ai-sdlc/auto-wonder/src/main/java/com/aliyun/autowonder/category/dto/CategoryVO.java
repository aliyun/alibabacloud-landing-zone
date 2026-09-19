package com.aliyun.autowonder.category.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class CategoryVO {
    private Long id;
    private Long parentId;
    private String name;
    private String description;
    /** 完整路径，如「编码 → 前端 → Vue」；仅用于展示与理解，关联始终使用稳定分类 ID。 */
    private String path;
    private Integer version;
    private Date gmtCreate;
    private Date gmtModified;
}
