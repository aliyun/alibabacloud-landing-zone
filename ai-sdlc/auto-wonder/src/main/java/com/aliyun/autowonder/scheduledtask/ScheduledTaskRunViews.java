package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskRunDetailVO;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskRunVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScheduledTaskRunViews {
    private ScheduledTaskRunViews() { }

    public static ScheduledTaskRunVO toVO(ScheduledTaskRunDO run) {
        ScheduledTaskRunVO value = new ScheduledTaskRunVO();
        copy(run, value);
        return value;
    }

    public static ScheduledTaskRunDetailVO toDetail(ScheduledTaskRunDO run) {
        ScheduledTaskRunDetailVO value = new ScheduledTaskRunDetailVO();
        copy(run, value);
        value.setSquadId(run.getSquadId()); value.setInitialAgentId(run.getInitialAgentId());
        value.setSessionMode(run.getSessionMode()); value.setResumeFromRunId(run.getResumeFromRunId());
        value.setOwnerId(run.getOwnerId());
        value.setSnapshot(snapshot(run.getExecutionSnapshotJson()));
        return value;
    }

    private static Map<String, Object> snapshot(String raw) {
        try {
            JSONObject root = JSON.parseObject(raw);
            if (root == null) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", root.getString("schemaVersion"));
            JSONObject task = root.getJSONObject("task");
            if (task != null) result.put("task", values("name", task.getString("name"), "instructionMd", task.getString("instructionMd")));
            JSONArray docs = root.getJSONArray("requirementDocuments");
            if (docs != null) result.put("requirementDocuments", docs.stream().filter(JSONObject.class::isInstance).map(JSONObject.class::cast).map(doc -> values("id", doc.get("artifactId"), "name", doc.getString("name"), "sha256", doc.getString("sha256"))).toList());
            JSONArray agents = root.getJSONArray("agentContexts");
            if (agents != null) result.put("agents", agents.stream().filter(JSONObject.class::isInstance).map(JSONObject.class::cast).map(agent -> values("agentId", agent.get("agentId"), "agentVersionId", agent.get("agentVersionId"), "identity", agent.getJSONObject("identity"), "sdlc", sdlc(agent.getJSONObject("sdlc")))).toList());
            result.put("policies", root.getJSONObject("policies"));
            return result;
        } catch (RuntimeException ignored) { return Map.of(); }
    }
    private static Map<String, Object> sdlc(JSONObject sdlc) {
        if (sdlc == null) return Map.of();
        List<Map<String, Object>> steps = sdlc.getJSONArray("steps") == null ? List.of() : sdlc.getJSONArray("steps").stream().filter(JSONObject.class::isInstance).map(JSONObject.class::cast).map(step -> Map.of("id", step.get("id"), "name", step.getString("name"))).toList();
        return values("id", sdlc.get("id"), "steps", steps);
    }
    private static Map<String, Object> values(Object... entries) { Map<String, Object> value = new LinkedHashMap<>(); for (int i = 0; i < entries.length; i += 2) value.put(String.valueOf(entries[i]), entries[i + 1]); return value; }

    private static void copy(ScheduledTaskRunDO run, ScheduledTaskRunVO value) {
        value.setId(run.getId()); value.setScheduledTaskId(run.getScheduledTaskId());
        value.setTriggerType(run.getTriggerType()); value.setScheduledAt(run.getScheduledAt());
        value.setStartedAt(run.getStartedAt()); value.setFinishedAt(run.getFinishedAt()); value.setStatus(run.getStatus());
        value.setSkipReason(run.getSkipReason()); value.setCurrentAgentId(run.getCurrentAgentId());
        value.setSdlcId(run.getSdlcId()); value.setCurrentStepId(run.getCurrentStepId());
        value.setDegradedResume(Integer.valueOf(1).equals(run.getDegradedResume()));
        value.setDegradedReason(run.getDegradedReason()); value.setResultSummary(run.getResultSummary());
        value.setError(run.getError()); value.setVersion(run.getVersion()); value.setGmtCreate(run.getGmtCreate());
        value.setGmtModified(run.getGmtModified());
    }
}
