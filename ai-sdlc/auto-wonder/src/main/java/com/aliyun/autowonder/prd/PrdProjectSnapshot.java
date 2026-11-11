package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.frontendproject.FrontendProjectProfileDO;
import lombok.Getter;
import lombok.Setter;

/** Server-owned target context included in the reviewed version's content digest. */
@Getter
@Setter
public class PrdProjectSnapshot {
    private Long repoId;
    private Long profileId;
    private String appRoot;
    private String commitSha;
    private String branch;
    private String detectorVersion;
    private String profileJson;
    private String evidenceJson;
    private String source;
    private String projectNotes;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isManual() {
        return "USER_CONFIGURED".equals(source);
    }

    public static PrdProjectSnapshot from(FrontendProjectProfileDO profile) {
        PrdProjectSnapshot result = new PrdProjectSnapshot();
        result.setRepoId(profile.getRepoId());
        result.setProfileId(profile.getId());
        result.setAppRoot(profile.getAppRoot());
        result.setCommitSha(profile.getCommitSha());
        result.setBranch(profile.getBranch());
        result.setDetectorVersion(profile.getDetectorVersion());
        result.setProfileJson(profile.getProfileJson());
        result.setEvidenceJson(profile.getEvidenceJson());
        return result;
    }
}
