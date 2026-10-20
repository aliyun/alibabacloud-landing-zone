package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

/** Read-only diagnostics, scoped to a workspace and an exact execution. */
@Service
public class DispatchDiagnosticService {
    public static final String PREFIX = "observability/runtime/hooks/receipts/";
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private final DispatchDao dispatchDao;
    private final ArtifactDao artifactDao;
    private final ObjectStorage storage;

    public DispatchDiagnosticService(DispatchDao dispatchDao, ArtifactDao artifactDao, ObjectStorage storage) {
        this.dispatchDao = dispatchDao;
        this.artifactDao = artifactDao;
        this.storage = storage;
    }

    public record Diagnostic(long id, String name, long sizeBytes) {}
    public record Content(long artifactId, String text, int offset, Integer nextOffset, int totalChars, boolean truncated) {}

    public List<Diagnostic> list(long workspaceId, long dispatchId) {
        requireDispatch(workspaceId, dispatchId);
        var unique = new LinkedHashMap<String, Diagnostic>();
        for (ArtifactDO artifact : artifactDao.listByDispatch(workspaceId, dispatchId)) {
            if (isDiagnostic(artifact)) unique.putIfAbsent(artifact.getName(),
                    new Diagnostic(artifact.getId(), artifact.getName(), artifact.getSize() == null ? 0 : artifact.getSize()));
        }
        return List.copyOf(unique.values());
    }

    public Content read(long workspaceId, long dispatchId, long artifactId, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 16000) throw new BizException(ErrorCode.PARAM_INVALID);
        ArtifactDO artifact = requireArtifact(workspaceId, dispatchId, artifactId);
        if (artifact.getSize() == null || artifact.getSize() > MAX_BYTES) throw new BizException(ErrorCode.PARAM_INVALID);
        byte[] raw = storage.get(artifact.getOssRef());
        if (raw == null) throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        if (raw.length > MAX_BYTES) throw new BizException(ErrorCode.PARAM_INVALID);
        String text = new String(raw, StandardCharsets.UTF_8);
        if (offset > text.length()) throw new BizException(ErrorCode.PARAM_INVALID);
        int end = Math.min(text.length(), offset + limit);
        // Never split a UTF-16 surrogate pair between pages.
        if (end > offset && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        if (end == offset && end < text.length()) end = Math.min(text.length(), end + 2);
        return new Content(artifactId, text.substring(offset, end), offset,
                end < text.length() ? end : null, text.length(), end < text.length());
    }

    public String download(long workspaceId, long dispatchId, long artifactId) {
        return storage.presignGet(requireArtifact(workspaceId, dispatchId, artifactId).getOssRef(), 600);
    }

    private ArtifactDO requireArtifact(long workspaceId, long dispatchId, long artifactId) {
        requireDispatch(workspaceId, dispatchId);
        // Query by dispatch as well as workspace; an arbitrary artifact id is insufficient.
        return artifactDao.listByDispatch(workspaceId, dispatchId).stream()
                .filter(a -> a.getId() == artifactId && isDiagnostic(a)).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.ARTIFACT_NOT_FOUND));
    }

    private boolean isDiagnostic(ArtifactDO artifact) {
        return artifact.getName() != null && artifact.getName().matches(
                "(?:artifacts/output/)?" + PREFIX + "[a-f0-9]{64}\\.json");
    }

    private void requireDispatch(long workspaceId, long dispatchId) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || dispatch.getTenantId() == null || dispatch.getTenantId() != workspaceId)
            throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
    }
}
