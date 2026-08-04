package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.dto.RuntimeTraceVO;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class RuntimeTraceArtifactService {

    private static final String TRACE_NAME = "observability/trace.json";
    private static final String CONTEXT_PREFIX = "context/files/";

    private final ArtifactDao artifactDao;
    private final ObjectStorage storage;

    public RuntimeTraceArtifactService(ArtifactDao artifactDao, ObjectStorage storage) {
        this.artifactDao = artifactDao;
        this.storage = storage;
    }

    public RuntimeTraceVO loadOutline(long tenantId, long dispatchId) {
        RuntimeTraceVO trace = loadTrace(tenantId, dispatchId);
        trace.setSource("OSS");
        for (RuntimeTraceVO.Session session : trace.getSessions()) {
            for (RuntimeTraceVO.Turn turn : session.getTurns()) {
                turn.setPrompt(null);
                turn.setSystemPrompt(null);
                turn.setOutput(null);
                stripObservationPayloads(turn.getObservations());
            }
        }
        return trace;
    }

    public RuntimeTraceVO loadOutlineIfPresent(long tenantId, long dispatchId) {
        ArtifactDO artifact = findArtifactOrNull(tenantId, dispatchId, TRACE_NAME);
        return artifact == null ? null : loadOutline(tenantId, dispatchId);
    }

    public RuntimeTraceVO.Turn loadTurn(long tenantId, long dispatchId, String traceId) {
        for (RuntimeTraceVO.Session session : loadTrace(tenantId, dispatchId).getSessions()) {
            for (RuntimeTraceVO.Turn turn : session.getTurns()) {
                if (traceId.equals(turn.getTraceId()) || traceId.equals(turn.getTurnId())) {
                    return turn;
                }
            }
        }
        throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
    }

    public RuntimeTraceVO.Observation loadObservation(long tenantId, long dispatchId, String observationId) {
        for (RuntimeTraceVO.Session session : loadTrace(tenantId, dispatchId).getSessions()) {
            for (RuntimeTraceVO.Turn turn : session.getTurns()) {
                RuntimeTraceVO.Observation found = findObservation(turn.getObservations(), observationId);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
    }

    public ContextContent loadContext(long tenantId, long dispatchId, String contentRef) {
        validateContentRef(contentRef);
        ArtifactDO artifact = findArtifact(tenantId, dispatchId, "observability/" + contentRef);
        byte[] bytes = storage.get(artifact.getOssRef());
        if (bytes == null) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        return new ContextContent(contentRef, bytes);
    }

    private RuntimeTraceVO loadTrace(long tenantId, long dispatchId) {
        ArtifactDO artifact = findArtifact(tenantId, dispatchId, TRACE_NAME);
        byte[] bytes = storage.get(artifact.getOssRef());
        if (bytes == null) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        RuntimeTraceVO trace = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8), RuntimeTraceVO.class);
        if (trace == null) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        try {
            trace.setDispatchId(Long.valueOf(String.valueOf(trace.getDispatchId())));
        } catch (RuntimeException ignored) {
            trace.setDispatchId(dispatchId);
        }
        return trace;
    }

    private ArtifactDO findArtifact(long tenantId, long dispatchId, String logicalName) {
        ArtifactDO artifact = findArtifactOrNull(tenantId, dispatchId, logicalName);
        if (artifact != null) {
            return artifact;
        }
        throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
    }

    private ArtifactDO findArtifactOrNull(long tenantId, long dispatchId, String logicalName) {
        List<ArtifactDO> artifacts = artifactDao.listByDispatch(tenantId, dispatchId);
        for (ArtifactDO artifact : artifacts) {
            String name = artifact.getName();
            if (logicalName.equals(name) || (name != null && name.endsWith("/" + logicalName))) {
                return artifact;
            }
        }
        return null;
    }

    private void stripObservationPayloads(List<RuntimeTraceVO.Observation> observations) {
        if (observations == null) {
            return;
        }
        for (RuntimeTraceVO.Observation observation : observations) {
            observation.setInput(null);
            observation.setOutput(null);
            observation.setError(null);
            stripObservationPayloads(observation.getChildren());
        }
    }

    private RuntimeTraceVO.Observation findObservation(List<RuntimeTraceVO.Observation> observations, String id) {
        if (observations == null) {
            return null;
        }
        for (RuntimeTraceVO.Observation observation : observations) {
            if (id.equals(observation.getObservationId())) {
                return observation;
            }
            RuntimeTraceVO.Observation child = findObservation(observation.getChildren(), id);
            if (child != null) {
                return child;
            }
        }
        return null;
    }

    private void validateContentRef(String contentRef) {
        if (contentRef == null || !contentRef.startsWith(CONTEXT_PREFIX)
                || contentRef.startsWith("/") || contentRef.contains("\\") || contentRef.contains("..")) {
            throw new IllegalArgumentException("invalid context content ref");
        }
    }

    public record ContextContent(String contentRef, byte[] bytes) {
    }
}
