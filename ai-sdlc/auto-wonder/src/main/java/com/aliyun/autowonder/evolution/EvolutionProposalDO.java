package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class EvolutionProposalDO {
    private Long id;
    private Long tenantId;
    private String assetType;
    private Long assetId;
    private String triggerType;
    private String rootEvidenceJson;
    private String policyJson;
    private String candidatePatchJson;
    private String status;
    private String lifecycleJson;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;

    public String getValidationJson() {
        return getLifecycleStage("validation");
    }

    public void setValidationJson(String validationJson) {
        setLifecycleStage("validation", validationJson);
    }

    public String getReplayJson() {
        return getLifecycleStage("replay");
    }

    public void setReplayJson(String replayJson) {
        setLifecycleStage("replay", replayJson);
    }

    public String getGateJson() {
        return getLifecycleStage("gates");
    }

    public void setGateJson(String gateJson) {
        setLifecycleStage("gates", gateJson);
    }

    public String getReleaseJson() {
        return getLifecycleStage("release");
    }

    public void setReleaseJson(String releaseJson) {
        setLifecycleStage("release", releaseJson);
    }

    public String getRollbackJson() {
        return getLifecycleStage("rollback");
    }

    public void setRollbackJson(String rollbackJson) {
        setLifecycleStage("rollback", rollbackJson);
    }

    public String getTrialJson() {
        return getLifecycleStage("trial");
    }

    public void setTrialJson(String trialJson) {
        setLifecycleStage("trial", trialJson);
    }

    private String getLifecycleStage(String stage) {
        JSONObject lifecycle = parseLifecycle();
        Object value = lifecycle.get(stage);
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        return JSON.toJSONString(value);
    }

    private void setLifecycleStage(String stage, String stageJson) {
        JSONObject lifecycle = parseLifecycle();
        if (stageJson == null || stageJson.isBlank()) {
            lifecycle.remove(stage);
        } else {
            lifecycle.put(stage, JSON.parse(stageJson));
        }
        lifecycleJson = lifecycle.isEmpty() ? null : lifecycle.toJSONString();
    }

    private JSONObject parseLifecycle() {
        if (lifecycleJson == null || lifecycleJson.isBlank()) {
            return new JSONObject(true);
        }
        JSONObject obj = JSON.parseObject(lifecycleJson);
        return obj == null ? new JSONObject(true) : obj;
    }
}
