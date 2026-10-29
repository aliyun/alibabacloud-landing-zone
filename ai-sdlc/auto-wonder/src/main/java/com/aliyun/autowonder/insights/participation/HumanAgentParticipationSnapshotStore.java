package com.aliyun.autowonder.insights.participation;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class HumanAgentParticipationSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(HumanAgentParticipationSnapshotStore.class);
    static final String KEY_PREFIX = "autowonder:insights:human-agent:v2:";
    static final String TAIL_KEY_PREFIX = "autowonder:insights:human-agent:tail:v1:";
    static final String CALIBER = "COMPLETION_FIRST_DONE";
    private static final int SCHEMA_VERSION = 3;

    private final RedisManager redisManager;
    private final HumanAgentParticipationProperties properties;

    public HumanAgentParticipationSnapshotStore(RedisManager redisManager,
                                                 HumanAgentParticipationProperties properties) {
        this.redisManager = redisManager;
        this.properties = properties;
    }

    public Optional<ParsedSnapshot> read(long tenantId) {
        return readKey(KEY_PREFIX + tenantId);
    }

    public Optional<ParsedSnapshot> readTail(long tenantId) {
        return readKey(TAIL_KEY_PREFIX + tenantId);
    }

    private Optional<ParsedSnapshot> readKey(String key) {
        try {
            String json = redisManager.getString(key);
            if (json == null || json.isBlank()) return Optional.empty();
            return parse(json);
        } catch (Exception e) {
            log.warn("Failed to read participation snapshot key={}", key, e);
            return Optional.empty();
        }
    }

    public void write(long tenantId, List<HumanAgentParticipationFact> facts,
                       String dataThrough, Instant generatedAt) {
        String json = serialize(facts, dataThrough, generatedAt);
        redisManager.setWithExpire(KEY_PREFIX + tenantId, json, properties.getCacheTtlSeconds());
        log.info("Participation snapshot written tenantId={} dataThrough={} items={} bytes={}",
                tenantId, dataThrough, facts.size(), json.length());
    }

    public void writeTail(long tenantId, List<HumanAgentParticipationFact> facts,
                          String dataThrough, Instant generatedAt) {
        String json = serialize(facts, dataThrough, generatedAt);
        redisManager.setWithExpire(TAIL_KEY_PREFIX + tenantId, json, properties.getTailCacheTtlSeconds());
        log.info("Participation realtime tail written tenantId={} dataThrough={} items={} bytes={}",
                tenantId, dataThrough, facts.size(), json.length());
    }

    public void deleteTail(long tenantId) {
        redisManager.del(TAIL_KEY_PREFIX + tenantId);
    }

    private String serialize(List<HumanAgentParticipationFact> facts, String dataThrough, Instant generatedAt) {
        JSONObject snapshot = new JSONObject(true);
        snapshot.put("schemaVersion", SCHEMA_VERSION);
        snapshot.put("caliber", CALIBER);
        snapshot.put("generatedAt", generatedAt.toString());
        snapshot.put("dataThrough", dataThrough);
        JSONArray items = new JSONArray();
        for (HumanAgentParticipationFact f : facts) {
            JSONObject item = new JSONObject(true);
            item.put("workitemId", f.workitemId());
            item.put("title", f.title());
            item.put("workType", f.workType());
            item.put("completedAt", f.completedAt().toString());
            item.put("totalDurationSeconds", f.totalDurationSeconds());
            item.put("humanDurationSeconds", f.humanDurationSeconds());
            item.put("agentDurationSeconds", f.agentDurationSeconds());
            item.put("createdAt", f.createdAt().toString());
            item.put("agentParticipated", f.agentParticipated());
            item.put("inferredAssignment", f.inferredAssignment());
            item.put("exclusionReason", f.exclusionReason());
            item.put("chainQuality", f.chainQuality());
            items.add(item);
        }
        snapshot.put("items", items);
        return snapshot.toJSONString();
    }

    Optional<ParsedSnapshot> parse(String json) {
        try {
            JSONObject root = JSON.parseObject(json);
            if (root == null) return Optional.empty();
            int version = root.getIntValue("schemaVersion");
            // v2 快照缺 chainQuality/caliber，按默认值兼容读取；v1 结构不兼容，直接重建。
            if (version != 2 && version != SCHEMA_VERSION) {
                log.warn("Participation snapshot schema mismatch version={}", version);
                return Optional.empty();
            }
            String caliber = root.getString("caliber");
            String generatedAt = root.getString("generatedAt");
            String dataThrough = root.getString("dataThrough");
            JSONArray itemsArray = root.getJSONArray("items");
            List<HumanAgentParticipationFact> facts = new ArrayList<>();
            if (itemsArray != null) {
                for (int i = 0; i < itemsArray.size(); i++) {
                    JSONObject item = itemsArray.getJSONObject(i);
                    HumanAgentParticipationFact fact = new HumanAgentParticipationFact(
                            item.getLongValue("workitemId"),
                            item.getString("title"),
                            Instant.parse(item.getString("completedAt")),
                            item.getLongValue("totalDurationSeconds"),
                            item.getLongValue("humanDurationSeconds"),
                            item.getLongValue("agentDurationSeconds"));
                    fact.setCreatedAt(Instant.parse(item.getString("createdAt")));
                    // 旧快照没有 workType，保持 null 以便按“匹配所有类型”处理。
                    fact.setWorkType(item.getString("workType"));
                    fact.setAgentParticipated(item.getBooleanValue("agentParticipated"));
                    fact.setInferredAssignment(item.getBooleanValue("inferredAssignment"));
                    fact.setChainQuality(item.getString("chainQuality"));
                    fact.setExclusionReason(item.getString("exclusionReason"));
                    facts.add(fact);
                }
            }
            return Optional.of(new ParsedSnapshot(generatedAt, dataThrough, facts,
                    caliber == null ? CALIBER : caliber));
        } catch (Exception e) {
            log.warn("Failed to parse participation snapshot", e);
            return Optional.empty();
        }
    }

    public static class ParsedSnapshot {
        private final String generatedAt;
        private final String dataThrough;
        private final List<HumanAgentParticipationFact> items;
        private final String caliber;

        public ParsedSnapshot(String generatedAt, String dataThrough,
                               List<HumanAgentParticipationFact> items) {
            this(generatedAt, dataThrough, items, CALIBER);
        }

        public ParsedSnapshot(String generatedAt, String dataThrough,
                               List<HumanAgentParticipationFact> items, String caliber) {
            this.generatedAt = generatedAt;
            this.dataThrough = dataThrough;
            this.items = items;
            this.caliber = caliber;
        }

        public String generatedAt() { return generatedAt; }
        public String dataThrough() { return dataThrough; }
        public List<HumanAgentParticipationFact> items() { return items; }
        public String caliber() { return caliber; }
    }
}
