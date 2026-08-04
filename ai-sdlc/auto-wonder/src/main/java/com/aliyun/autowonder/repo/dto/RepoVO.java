package com.aliyun.autowonder.repo.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class RepoVO {
    private Long id;
    private String name;
    private String url;
    private String defaultBranch;
    private String description;
    private String scanStatus;
    private Integer version;
    private Date gmtCreate;
}
