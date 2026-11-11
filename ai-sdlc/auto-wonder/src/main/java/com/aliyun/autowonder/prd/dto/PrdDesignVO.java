package com.aliyun.autowonder.prd.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** The design aggregate for one workitem: target project context, confirmation state, versions. */
@Getter
@Setter
public class PrdDesignVO {
    private Long id;
    private Long workitemId;
    private Long targetRepoId;
    private Long targetProfileId;
    private String targetAppRoot;
    private String targetCommitSha;
    private String targetProjectNotes;
    private Long confirmedVersionId;
    private Integer confirmedVersionNo;
    private String confirmedSha256;
    private String deliveryMode;
    private Integer latestVersionNo;
    private String contentLanguage;
    private Integer version;
    private Date gmtModified;
    private List<PrdDesignVersionVO> versions = new ArrayList<>();
}
