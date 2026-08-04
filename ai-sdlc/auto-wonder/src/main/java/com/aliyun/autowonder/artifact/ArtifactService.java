package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.artifact.dto.ReportArtifactRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ArtifactService {

    private static final int DOWNLOAD_TTL_SECONDS = 600;
    private static final long MAX_PREVIEW_BYTES = 20L * 1024L * 1024L;

    private final ArtifactDao artifactDao;
    private final ObjectStorage storage;

    public ArtifactService(ArtifactDao artifactDao, ObjectStorage storage) {
        this.artifactDao = artifactDao;
        this.storage = storage;
    }

    public Long record(ReportArtifactRequest req, long tenantId) {
        ArtifactDO a = new ArtifactDO();
        a.setTenantId(tenantId);
        a.setWorkitemId(req.getWorkitemId());
        a.setDispatchId(req.getDispatchId());
        a.setName(req.getName());
        a.setType(req.getType());
        a.setOssRef(req.getOssRef());
        a.setSize(req.getSize());
        a.setMetaJson(req.getMetaJson());
        artifactDao.insert(a);
        return a.getId();
    }

    public List<ArtifactVO> listByWorkitem(long workitemId, long tenantId) {
        Map<String, ArtifactDO> latestByLogicalName = new LinkedHashMap<>();
        for (ArtifactDO a : artifactDao.listByWorkitem(tenantId, workitemId)) {
            if (!isUserVisible(a)) {
                continue;
            }
            latestByLogicalName.putIfAbsent(logicalName(a.getName()), a);
        }
        List<ArtifactVO> result = new ArrayList<>();
        for (ArtifactDO artifact : latestByLogicalName.values()) {
            result.add(toVO(artifact));
        }
        return result;
    }

    public List<ArtifactVO> listByDispatch(long dispatchId, long tenantId) {
        List<ArtifactVO> result = new ArrayList<>();
        for (ArtifactDO a : artifactDao.listByDispatch(tenantId, dispatchId)) {
            if (isUserVisible(a)) {
                result.add(toVO(a));
            }
        }
        return result;
    }

    private boolean isUserVisible(ArtifactDO artifact) {
        if (artifact == null) {
            return false;
        }
        String name = artifact.getName();
        return name == null || !(name.startsWith("observability/") || name.contains("/observability/"));
    }

    private String logicalName(String name) {
        if (name == null) {
            return "";
        }
        if (name.startsWith("artifacts/output/")) {
            return name.substring("artifacts/output/".length());
        }
        if (name.startsWith("output/")) {
            return name.substring("output/".length());
        }
        return name;
    }

    public String getDownloadUrl(long id, long tenantId) {
        ArtifactDO a = artifactDao.findById(id);
        if (a == null || a.getTenantId() == null || !a.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        return forceHttps(storage.presignGet(a.getOssRef(), DOWNLOAD_TTL_SECONDS));
    }

    public PreviewContent getPreviewContent(long id, long tenantId) {
        ArtifactDO a = artifactDao.findById(id);
        if (a == null || a.getTenantId() == null || !a.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        if (!isPreviewable(a.getName()) || a.getSize() == null || a.getSize() > MAX_PREVIEW_BYTES) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        byte[] bytes = storage.get(a.getOssRef());
        if (bytes == null) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        return new PreviewContent(a.getName(), bytes);
    }

    private String forceHttps(String url) {
        if (url != null && url.startsWith("http://")) {
            return "https://" + url.substring("http://".length());
        }
        return url;
    }

    private boolean isPreviewable(String name) {
        String ext = extension(name);
        switch (ext) {
            case "md":
            case "markdown":
            case "txt":
            case "log":
            case "json":
            case "jsonl":
            case "csv":
            case "png":
            case "jpg":
            case "jpeg":
            case "gif":
            case "webp":
            case "mp4":
            case "webm":
            case "ogg":
            case "ogv":
            case "mov":
            case "m4v":
                return true;
            default:
                return false;
        }
    }

    private String extension(String name) {
        if (name == null) {
            return "";
        }
        int query = name.indexOf('?');
        String clean = query >= 0 ? name.substring(0, query) : name;
        int hash = clean.indexOf('#');
        clean = hash >= 0 ? clean.substring(0, hash) : clean;
        int dot = clean.lastIndexOf('.');
        return dot >= 0 ? clean.substring(dot + 1).toLowerCase() : "";
    }

    private ArtifactVO toVO(ArtifactDO a) {
        ArtifactVO vo = new ArtifactVO();
        vo.setId(a.getId());
        vo.setWorkitemId(a.getWorkitemId());
        vo.setDispatchId(a.getDispatchId());
        vo.setName(a.getName());
        vo.setType(a.getType());
        vo.setSize(a.getSize());
        vo.setGmtCreate(a.getGmtCreate());
        return vo;
    }

    public static class PreviewContent {
        private final String name;
        private final byte[] bytes;

        public PreviewContent(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }

        public String getName() {
            return name;
        }

        public byte[] getBytes() {
            return bytes;
        }
    }
}
