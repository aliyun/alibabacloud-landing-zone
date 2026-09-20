package com.aliyun.autowonder.category.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateCategoryRequest {
    private String name;
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = CategoryParentIdDeserializer.class)
    private Long parentId;
    private String description;
}
