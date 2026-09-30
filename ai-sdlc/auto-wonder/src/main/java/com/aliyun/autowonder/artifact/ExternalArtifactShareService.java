package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.StoredObject;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单产物对外只读分享：SDLC/Agent（dispatch 凭证）或工作区成员经 MCP expose 工具决定
 * 哪些产物对外可见（仅结论类，Agent 自查脱敏）；外部读者（如 Aone 工单评论用户）凭
 * workitem 级分享令牌免登录只读访问，令牌在首次暴露产物时生成、长期有效。
 *
 * 轮次语义：按 name 解析时若有 dispatchId（dispatch 凭证强制为自己所在派发），只在该派发
 * 内解析；MCP 本轮申请由 ArtifactShareRequestService 持久化等待，绝不回退上一轮同名产物。
 *
 * 快照语义：首次暴露时把内容固化到独立 share key（external_share_ref）；此后同派发同名
 * 重传只覆盖 live oss_ref 与 artifact 行，不改写快照，已分享链接永远呈现首次暴露时的内容。
 */
@Service
public class ExternalArtifactShareService {

    static final String TOKEN_PREFIX = "awshare_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ArtifactDao artifactDao;
    private final WorkitemDao workitemDao;
    private final ObjectStorage storage;
    private final PlatformBrandingService brandingService;

    public ExternalArtifactShareService(ArtifactDao artifactDao, WorkitemDao workitemDao,
                                        ObjectStorage storage, PlatformBrandingService brandingService) {
        this.artifactDao = artifactDao;
        this.workitemDao = workitemDao;
        this.storage = storage;
        this.brandingService = brandingService;
    }

    public Map<String, Object> expose(long workspaceId, long workitemId, Long dispatchId,
                                      Long artifactId, String name) {
        return expose(workspaceId, workitemId, dispatchId, artifactId, name, null);
    }

    /** expectedSha256 pins the exact reviewed bytes, including an existing frozen snapshot. */
    public Map<String, Object> expose(long workspaceId, long workitemId, Long dispatchId,
                                      Long artifactId, String name, String expectedSha256) {
        if (artifactId == null && (name == null || name.isBlank())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "artifactId 与 name 至少提供一个");
        }
        if (dispatchId != null && dispatchId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "dispatchId 必须是正整数");
        }
        WorkitemDO workitem = workitemDao.findById(workitemId);
        if (workitem == null || !Long.valueOf(workspaceId).equals(workitem.getTenantId())) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        ArtifactDO artifact;
        if (artifactId != null) {
            artifact = artifactDao.findWorkitemByTenantAndId(workspaceId, artifactId);
        } else if (dispatchId != null) {
            artifact = findByLogicalNameInDispatch(workspaceId, dispatchId, name);
        } else {
            artifact = findByLogicalName(workspaceId, workitemId, name);
        }
        if (artifact == null || !Long.valueOf(workitemId).equals(artifact.getWorkitemId())
                || (dispatchId != null && !dispatchId.equals(artifact.getDispatchId()))
                || (artifact.getSourceType() != null && !"WORKITEM".equals(artifact.getSourceType()))) {
            if (dispatchId != null) {
                throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND,
                        "该派发下未找到此产物（本轮可能尚未上传，请勿回退历史轮次）");
            }
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        String token = ensureWorkitemShareToken(workitem);
        snapshotOnFirstExpose(artifact, expectedSha256);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SHARED");
        result.put("artifactId", artifact.getId());
        result.put("name", logicalName(artifact.getName()));
        result.put("type", artifact.getType());
        result.put("artifactUrl", artifactUrl(token, artifact.getId()));
        result.put("directoryUrl", directoryUrl(token));
        return result;
    }

    /** 工单级最新版本（人工暴露存量产物场景）。 */
    private ArtifactDO findByLogicalName(long workspaceId, long workitemId, String name) {
        String wanted = logicalName(name.trim());
        for (ArtifactDO candidate : artifactDao.listByWorkitem(workspaceId, workitemId)) {
            if (wanted.equals(logicalName(candidate.getName()))) {
                return candidate;
            }
        }
        return null;
    }

    /** 仅在指定派发内解析；listByDispatch 按 id 倒序，唯一键保证同名至多一行。 */
    private ArtifactDO findByLogicalNameInDispatch(long workspaceId, long dispatchId, String name) {
        String wanted = logicalName(name.trim());
        for (ArtifactDO candidate : artifactDao.listByDispatch(workspaceId, dispatchId)) {
            if (wanted.equals(logicalName(candidate.getName()))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 快照固化（CAS）：先读 live 内容写入"候选"key（带随机后缀，并发各方互不覆盖），
     * 再由 markExternalExposed 的 external_share_ref IS NULL 条件原子选定引用——竞败者
     * 的候选对象成为无引用孤儿（不可达，可被桶生命周期清理），不影响已生效快照。
     * 注意：候选 key 不能用确定性名字，否则并发窗口内 live 对象更新时，后完成方仍会
     * 覆盖先选定方的快照内容。
     * ponytail: 全量字节内存复制，上限 50MB（daemon 上传单文件上限）；超大产物再改流式。
     */
    private void snapshotOnFirstExpose(ArtifactDO artifact, String expectedSha256) {
        ArtifactDO exposed = artifactDao.findExternalExposed(artifact.getTenantId(), artifact.getWorkitemId(),
                artifact.getId());
        if (exposed != null) {
            if (expectedSha256 != null) verifyDigest(loadContent(exposed), expectedSha256);
            return;
        }
        byte[] content = storage.get(artifact.getOssRef());
        if (content == null) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        if (expectedSha256 != null) verifyDigest(content, expectedSha256);
        String bucket = bucketOf(artifact.getOssRef());
        String candidateKey = "t/" + artifact.getTenantId() + "/share/" + artifact.getWorkitemId()
                + "/" + artifact.getId() + "/" + candidateSuffix(logicalName(artifact.getName()));
        StoredObject stored = storage.put(bucket, candidateKey, content);
        int updated = artifactDao.markExternalExposed(artifact.getTenantId(), artifact.getWorkitemId(),
                artifact.getId(), stored.getOssRef());
        if (updated == 0 && expectedSha256 != null) {
            ArtifactDO winner = artifactDao.findExternalExposed(artifact.getTenantId(), artifact.getWorkitemId(),
                    artifact.getId());
            if (winner == null) throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
            verifyDigest(loadContent(winner), expectedSha256);
        }
    }

    static void verifyDigest(byte[] content, String expectedSha256) {
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            if (!actual.equals(expectedSha256)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "分享内容 SHA-256 不匹配，未公开此版本");
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 候选快照文件名含随机段，防止并发覆盖。 */
    private static String candidateSuffix(String logicalName) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        String random = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        int dot = logicalName.lastIndexOf('.');
        if (dot <= 0) {
            return logicalName + "." + random;
        }
        return logicalName.substring(0, dot) + "." + random + logicalName.substring(dot);
    }

    public WorkitemDO findWorkitemByShareToken(String token) {
        return workitemDao.findByExternalShareToken(token);
    }

    public List<ArtifactDO> listExposed(long tenantId, long workitemId) {
        return artifactDao.listExternalExposed(tenantId, workitemId);
    }

    public ArtifactDO findExposedArtifact(long tenantId, long workitemId, long artifactId) {
        return artifactDao.findExternalExposed(tenantId, workitemId, artifactId);
    }

    /** 对外只读快照内容；live oss_ref 的后续变化不影响此处返回。 */
    public byte[] loadContent(ArtifactDO artifact) {
        String snapshotRef = artifact.getExternalShareRef();
        if (snapshotRef == null) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        byte[] bytes = storage.get(snapshotRef);
        if (bytes == null) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        return bytes;
    }

    public String directoryUrl(String token) {
        return baseUrl() + "/api/share/workitems/" + token;
    }

    public String artifactUrl(String token, long artifactId) {
        return directoryUrl(token) + "/artifacts/" + artifactId;
    }

    String requestUrl(WorkitemDO workitem, long requestId) {
        return directoryUrl(ensureWorkitemShareToken(workitem)) + "/requests/" + requestId;
    }

    private String ensureWorkitemShareToken(WorkitemDO workitem) {
        if (workitem.getExternalShareToken() != null) {
            return workitem.getExternalShareToken();
        }
        String token = newToken();
        if (workitemDao.updateExternalShareTokenIfAbsent(workitem.getId(), workitem.getTenantId(), token) == 1) {
            return token;
        }
        // 并发首写竞败：回读取胜者令牌，保证同工单令牌唯一稳定
        WorkitemDO fresh = workitemDao.findById(workitem.getId());
        if (fresh == null || fresh.getExternalShareToken() == null) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        return fresh.getExternalShareToken();
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String baseUrl() {
        String base = brandingService.effectivePublicBaseUrl();
        return base != null && base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String bucketOf(String ossRef) {
        int slash = ossRef.indexOf('/');
        if (slash <= 0) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND, "bad ossRef");
        }
        return ossRef.substring(0, slash);
    }

    static String logicalName(String name) {
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
}
