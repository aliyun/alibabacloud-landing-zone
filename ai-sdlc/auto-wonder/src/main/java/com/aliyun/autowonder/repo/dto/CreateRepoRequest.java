package com.aliyun.autowonder.repo.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateRepoRequest {
    private String name;
    private String url;
    private String defaultBranch;
    private String description;
}
