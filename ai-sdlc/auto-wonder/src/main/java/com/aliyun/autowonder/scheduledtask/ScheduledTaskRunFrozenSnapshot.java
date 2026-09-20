package com.aliyun.autowonder.scheduledtask;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single source of truth for the frozen participant snapshot of a run: the
 * mention candidate list and the comment-time guidance validation must agree,
 * otherwise the UI offers targets the write path rejects.
 */
final class ScheduledTaskRunFrozenSnapshot {
    private ScheduledTaskRunFrozenSnapshot() { }

    static Set<Long> frozenAgentIds(ScheduledTaskRunDO run) {
        Set<Long> agentIds = new LinkedHashSet<>();
        if (run == null) return agentIds;
        try {
            JSONObject root = JSON.parseObject(run.getExecutionSnapshotJson());
            JSONArray contexts = root == null ? null : root.getJSONArray("agentContexts");
            for (int i = 0; contexts != null && i < contexts.size(); i++) {
                JSONObject context = contexts.getJSONObject(i);
                if (context != null && context.getLong("agentId") != null) {
                    agentIds.add(context.getLong("agentId"));
                }
            }
        } catch (RuntimeException ignored) {
            // A malformed snapshot contributes no frozen participants instead of failing the request.
        }
        return agentIds;
    }

    static boolean isFrozenParticipant(ScheduledTaskRunDO run, long agentId) {
        return frozenAgentIds(run).contains(agentId);
    }
}
