package com.aliyun.autowonder.memory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.memory.dto.CreateMemoryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class MemorySedimentationService {

    private static final Logger log = LoggerFactory.getLogger(MemorySedimentationService.class);
    private static final long SYSTEM_USER_ID = 0L;

    private final MemoryService memoryService;

    public MemorySedimentationService(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public void ingest(long tenantId, long agentId, long dispatchId, byte[] json) {
        try {
            JSONObject root = JSON.parseObject(new String(json, StandardCharsets.UTF_8));
            JSONArray entries = root == null ? null : root.getJSONArray("entries");
            if (entries == null) {
                return;
            }
            for (int i = 0; i < entries.size(); i++) {
                JSONObject e = entries.getJSONObject(i);
                if (!"memory".equals(e.getString("type"))) {
                    continue;
                }
                String content = e.getString("content");
                if (content == null || content.isBlank()) {
                    continue;
                }
                String title = e.getString("title");
                if (title == null || title.isBlank()) {
                    title = firstLine(content);
                }
                CreateMemoryRequest req = new CreateMemoryRequest();
                req.setScope("AGENT");
                req.setOwnerRef(agentId);
                req.setType("memory");
                req.setTitle(title);
                req.setContentMd(content);
                memoryService.createFromLearningDelta(req, tenantId, dispatchId, i);
            }
        } catch (Exception ex) {
            log.warn("memory sedimentation skipped dispatchId={}", dispatchId, ex);
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String line = nl > 0 ? s.substring(0, nl).trim() : s.trim();
        return line.length() > 200 ? line.substring(0, 200) : line;
    }
}
