package com.aliyun.autowonder.agent.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RepoPermRequest {
    private Long repoId;
    private String permLevel;
}
