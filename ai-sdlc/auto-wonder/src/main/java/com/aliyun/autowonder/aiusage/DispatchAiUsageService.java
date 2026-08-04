package com.aliyun.autowonder.aiusage;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.aiusage.dto.DispatchAiUsageBackfillResult;
import com.aliyun.autowonder.aiusage.dto.TaskUsageReportRequest;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Service
public class DispatchAiUsageService {

    private static final Logger log = LoggerFactory.getLogger(DispatchAiUsageService.class);
    private static final String USAGE_ARTIFACT_LOGICAL_PATH = "observability/usage.json";
    private static final int BACKFILL_BATCH_SIZE = 200;

    private final DispatchAiUsageDao usageDao;
    private final DispatchDao dispatchDao;
    private final ArtifactDao artifactDao;
    private final ObjectStorage storage;

    public DispatchAiUsageService(DispatchAiUsageDao usageDao, DispatchDao dispatchDao,
                                  ArtifactDao artifactDao, ObjectStorage storage) {
        this.usageDao = usageDao;
        this.dispatchDao = dispatchDao;
        this.artifactDao = artifactDao;
        this.storage = storage;
    }

    public void recordTaskUsage(long tenantId, long dispatchId, List<TaskUsageReportRequest.TaskUsageEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || dispatch.getTenantId() == null || dispatch.getTenantId() != tenantId) {
            log.warn("task usage skipped dispatchId={} tenantId={} reason=dispatch_not_found", dispatchId, tenantId);
            return;
        }
        persistEntries(dispatch, null, entries, new Date());
    }

    public void ingestArtifact(long tenantId, long workitemId, long dispatchId, Long artifactId,
                               String artifactName, String ossRef, byte[] content) {
        if (!isUsageArtifact(artifactName)) {
            return;
        }
        try {
            byte[] bytes = content != null ? content : storage.get(ossRef);
            if (bytes == null) {
                log.warn("usage artifact ingest skipped artifactId={} ossRef={} workitemId={} dispatchId={} reason=empty_content",
                        artifactId, ossRef, workitemId, dispatchId);
                return;
            }
            List<TaskUsageReportRequest.TaskUsageEntry> entries = parseUsageEntries(bytes);
            if (entries.isEmpty()) {
                log.warn("usage artifact ingest skipped artifactId={} ossRef={} workitemId={} dispatchId={} reason=no_entries",
                        artifactId, ossRef, workitemId, dispatchId);
                return;
            }
            DispatchDO dispatch = dispatchDao.findById(dispatchId);
            if (dispatch == null || dispatch.getTenantId() == null || dispatch.getTenantId() != tenantId) {
                log.warn("usage artifact ingest skipped artifactId={} ossRef={} workitemId={} dispatchId={} reason=dispatch_not_found",
                        artifactId, ossRef, workitemId, dispatchId);
                return;
            }
            persistEntries(dispatch, artifactId, entries, new Date());
        } catch (RuntimeException ex) {
            log.warn("usage artifact ingest failed artifactId={} ossRef={} workitemId={} dispatchId={}",
                    artifactId, ossRef, workitemId, dispatchId, ex);
        }
    }

    public DispatchAiUsageBackfillResult backfillUsageArtifacts(long tenantId) {
        DispatchAiUsageBackfillResult result = new DispatchAiUsageBackfillResult();
        int offset = 0;
        while (true) {
            List<ArtifactDO> artifacts = artifactDao.listUsageArtifacts(tenantId, USAGE_ARTIFACT_LOGICAL_PATH, offset, BACKFILL_BATCH_SIZE);
            if (artifacts == null || artifacts.isEmpty()) {
                break;
            }
            for (ArtifactDO artifact : artifacts) {
                result.scanned();
                try {
                    byte[] bytes = storage.get(artifact.getOssRef());
                    if (bytes == null || artifact.getDispatchId() == null || artifact.getWorkitemId() == null) {
                        result.skipped();
                        continue;
                    }
                    List<TaskUsageReportRequest.TaskUsageEntry> entries = parseUsageEntries(bytes);
                    if (entries.isEmpty()) {
                        result.skipped();
                        continue;
                    }
                    DispatchDO dispatch = dispatchDao.findById(artifact.getDispatchId());
                    if (dispatch == null || dispatch.getTenantId() == null || dispatch.getTenantId() != tenantId) {
                        result.skipped();
                        continue;
                    }
                    persistEntries(dispatch, artifact.getId(), entries, artifact.getGmtCreate() != null ? artifact.getGmtCreate() : new Date());
                    result.succeeded();
                } catch (RuntimeException ex) {
                    result.failed();
                    log.warn("usage artifact backfill failed artifactId={} ossRef={} workitemId={} dispatchId={}",
                            artifact.getId(), artifact.getOssRef(), artifact.getWorkitemId(), artifact.getDispatchId(), ex);
                }
            }
            if (artifacts.size() < BACKFILL_BATCH_SIZE) {
                break;
            }
            offset += BACKFILL_BATCH_SIZE;
        }
        log.info("usage artifact backfill finished tenantId={} scanned={} succeeded={} skipped={} failed={}",
                tenantId, result.getScanned(), result.getSucceeded(), result.getSkipped(), result.getFailed());
        return result;
    }

    private void persistEntries(DispatchDO dispatch, Long artifactId,
                                List<TaskUsageReportRequest.TaskUsageEntry> entries, Date usageAt) {
        for (TaskUsageReportRequest.TaskUsageEntry entry : entries) {
            String provider = normalize(entry.getProvider());
            String model = normalize(entry.getModel());
            long inputTokens = nonNegative(entry.getInputTokens());
            long outputTokens = nonNegative(entry.getOutputTokens());
            long cacheReadTokens = nonNegative(entry.getCacheReadTokens());
            long cacheWriteTokens = nonNegative(entry.getCacheWriteTokens());
            long totalTokens = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;

            DispatchAiUsageDO usage = new DispatchAiUsageDO();
            usage.setTenantId(dispatch.getTenantId());
            usage.setWorkitemId(dispatch.getWorkitemId());
            usage.setDispatchId(dispatch.getId());
            usage.setAgentId(dispatch.getAgentId());
            usage.setExecutorId(dispatch.getExecutorId());
            usage.setArtifactId(artifactId);
            usage.setProvider(provider);
            usage.setModel(model);
            usage.setInputTokens(inputTokens);
            usage.setOutputTokens(outputTokens);
            usage.setCacheReadTokens(cacheReadTokens);
            usage.setCacheWriteTokens(cacheWriteTokens);
            usage.setTotalTokens(totalTokens);
            usage.setRawJson(JSON.toJSONString(entry));
            usage.setUsageAt(usageAt);
            usageDao.upsert(usage);
            log.info("dispatch usage recorded tenantId={} workitemId={} dispatchId={} provider={} model={} totalTokens={}",
                    dispatch.getTenantId(), dispatch.getWorkitemId(), dispatch.getId(), provider, model, totalTokens);
        }
    }

    private List<TaskUsageReportRequest.TaskUsageEntry> parseUsageEntries(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        Object parsed = JSON.parse(text);
        JSONArray usageArray;
        if (parsed instanceof JSONArray) {
            usageArray = (JSONArray) parsed;
        } else if (parsed instanceof JSONObject) {
            usageArray = ((JSONObject) parsed).getJSONArray("usage");
        } else {
            return Collections.emptyList();
        }
        if (usageArray == null || usageArray.isEmpty()) {
            return Collections.emptyList();
        }
        return usageArray.toJavaList(TaskUsageReportRequest.TaskUsageEntry.class);
    }

    private boolean isUsageArtifact(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.replace('\\', '/');
        return normalized.equals(USAGE_ARTIFACT_LOGICAL_PATH)
                || normalized.endsWith("/" + USAGE_ARTIFACT_LOGICAL_PATH);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private long nonNegative(Long value) {
        return value == null || value < 0 ? 0 : value;
    }
}
