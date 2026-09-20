package com.aliyun.autowonder.agent.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class RepoPermRequest {
    private Long repoId;
    private String permLevel;
    private List<String> allowedBranchPatterns;
}
