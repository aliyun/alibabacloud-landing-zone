package com.aliyun.autowonder.insights.participation;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
public class HumanAgentParticipationSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(HumanAgentParticipationSnapshotStore.class);
    static final String KEY_PREFIX = "autowonder:insights:human-agent:v1:";
    private static final int SCHEMA_VERSION = 1;

    private final RedisManager redisManager;
    private final HumanAgentParticipationProperties properties;

    public HumanAgentParticipationSnapshotStore(RedisManager redisManager,
                                                 HumanAgentParticipationProperties properties) {
        this.redisManager = redisManager;
        this.properties = properties;
    }

    public Optional<ParsedSnapshot> read(long tenantId) {
        try {
            String json = redisManager.getString(KEY_PREFIX + tenantId);
            if (json == null || json.isBlank()) return Optional.empty();
            return parse(json);
        } catch (Exception e) {
            log.warn("Failed to read participation snapshot tenantId={}", tenantId, e);
            return Optional.empty();
        }
    }

    public void write(long tenantId, List<HumanAgentParticipationFact> facts,
                       String dataThrough, Instant generatedAt) {
        JSONObject snapshot = new JSONObject(true);
        snapshot.put("schemaVersion", SCHEMA_VERSION);
        snapshot.put("generatedAt", generatedAt.toString());
        snapshot.put("dataThrough", dataThrough);
        JSONArray items = new JSONArray();
        for (HumanAgentParticipationFact f : facts) {
            JSONObject item = new JSONObject(true);
            item.put("workitemId", f.workitemId());
            item.put("title", f.title());
            item.put("completedAt", f.completedAt().toString());
            item.put("totalDurationSeconds", f.totalDurationSeconds());
            item.put("humanDurationSeconds", f.humanDurationSeconds());
            item.put("agentDurationSeconds", f.agentDurationSeconds());
            items.add(item);
        }
        snapshot.put("items", items);
        String json = snapshot.toJSONString();
        redisManager.setWithExpire(KEY_PREFIX + tenantId, json, properties.getCacheTtlSeconds());
        log.info("Participation snapshot written tenantId={} dataThrough={} items={} bytes={}",
                tenantId, dataThrough, facts.size(), json.length());
    }

    Optional<ParsedSnapshot> parse(String json) {
        try {
            JSONObject root = JSON.parseObject(json);
            if (root == null) return Optional.empty();
            int version = root.getIntValue("schemaVersion");
            if (version != SCHEMA_VERSION) {
                log.warn("Participation snapshot schema mismatch version={}", version);
                return Optional.empty();
            }
            String generatedAt = root.getString("generatedAt");
            String dataThrough = root.getString("dataThrough");
            JSONArray itemsArray = root.getJSONArray("items");
            List<HumanAgentParticipationFact> facts = new ArrayList<>();
            if (itemsArray != null) {
                for (int i = 0; i < itemsArray.size(); i++) {
                    JSONObject item = itemsArray.getJSONObject(i);
                    facts.add(new HumanAgentParticipationFact(
                            item.getLongValue("workitemId"),
                            item.getString("title"),
                            Instant.parse(item.getString("completedAt")),
                            item.getLongValue("totalDurationSeconds"),
                            item.getLongValue("humanDurationSeconds"),
                            item.getLongValue("agentDurationSeconds")));
                }
            }
            return Optional.of(new ParsedSnapshot(generatedAt, dataThrough, facts));
        } catch (Exception e) {
            log.warn("Failed to parse participation snapshot", e);
            return Optional.empty();
        }
    }

    public static class ParsedSnapshot {
        private final String generatedAt;
        private final String dataThrough;
        private final List<HumanAgentParticipationFact> items;

        public ParsedSnapshot(String generatedAt, String dataThrough,
                               List<HumanAgentParticipationFact> items) {
            this.generatedAt = generatedAt;
            this.dataThrough = dataThrough;
            this.items = items;
        }

        public String generatedAt() { return generatedAt; }
        public String dataThrough() { return dataThrough; }
        public List<HumanAgentParticipationFact> items() { return items; }
    }
}
