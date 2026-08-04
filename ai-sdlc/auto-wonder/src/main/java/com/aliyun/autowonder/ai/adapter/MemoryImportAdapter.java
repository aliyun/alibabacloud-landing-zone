package com.aliyun.autowonder.ai.adapter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.memory.MemoryDO;
import com.aliyun.autowonder.memory.MemoryDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MemoryImportAdapter implements SceneAdapter {

    private static final Logger log = LoggerFactory.getLogger(MemoryImportAdapter.class);

    private final MemoryDao memoryDao;

    public MemoryImportAdapter(MemoryDao memoryDao) {
        this.memoryDao = memoryDao;
    }

    @Override
    public String scene() {
        return AiConstants.Scene.MEMORY_IMPORT;
    }

    @Override
    public String buildSystemPrompt(AiSessionDO session) {
        return "你是知识提炼专家。你的目标是通过对话帮助用户将文本或文档提炼为准确的结构化记忆条目。\n\n" +
                "工作方式：\n" +
                "1. 先阅读用户提供的内容，提炼出关键知识点。\n" +
                "2. 用列表展示你拟定的记忆条目（标题、类型、摘要），询问用户是否需要增删或调整。\n" +
                "3. 用户确认后，输出最终JSON。\n\n" +
                "最终输出的JSON格式：\n" +
                "{\"items\":[{\"type\":\"项目知识|工程规则|经验|偏好|避坑\", " +
                "\"title\":\"...\", \"contentMd\":\"...\"}]}\n\n" +
                "在用户确认之前不要输出JSON。";
    }

    @Override
    public String buildUserPrompt(AiSessionDO session, String userInput) {
        if (userInput != null && !userInput.isBlank()) {
            return userInput;
        }
        return "请提炼以下内容为记忆条目。";
    }

    @Override
    public String validateResult(String resultJson) {
        try {
            JSONObject obj = JSON.parseObject(resultJson);
            if (obj == null) return "null result";
            JSONArray items = obj.getJSONArray("items");
            if (items == null || items.isEmpty()) {
                return "items array is empty";
            }
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item.getString("title") == null || item.getString("title").isBlank()) {
                    return "item[" + i + "] missing title";
                }
            }
            return null;
        } catch (Exception e) {
            return "invalid JSON: " + e.getMessage();
        }
    }

    @Override
    public void persistConfirmedResult(AiSessionDO session, String resultJson) {
        JSONObject obj = JSON.parseObject(resultJson);
        JSONArray items = obj.getJSONArray("items");
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            MemoryDO m = new MemoryDO();
            m.setTenantId(session.getTenantId());
            m.setScope("ORG");
            m.setOwnerRef(session.getBizRefId());
            m.setType(item.getString("type"));
            m.setTitle(item.getString("title"));
            m.setContentMd(item.getString("contentMd"));
            m.setStatus("PENDING");
            m.setSource("AI_IMPORT");
            m.setSourceRef(JSON.toJSONString(Map.of("aiSessionId", session.getId())));
            m.setVersion(0);
            memoryDao.insert(m);
            count++;
        }
        log.info("memory imported via AI sessionId={} count={}", session.getId(), count);
    }
}
