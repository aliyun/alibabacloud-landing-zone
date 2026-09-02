package com.aliyun.autowonder.skill;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.dto.CreateSkillRequest;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.skill.dto.UpdateSkillRequest;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SkillService {

    private static final Pattern HTTP_HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Set<String> RESERVED_HEADER_NAMES = Set.of("host", "content-length", "connection", "transfer-encoding");

    private final SkillDao skillDao;
    private final AgentSkillDao agentSkillDao;
    private final UserDao userDao;
    private final SecretCrypto secretCrypto;

    public SkillService(SkillDao skillDao, AgentSkillDao agentSkillDao, UserDao userDao) {
        this(skillDao, agentSkillDao, userDao, null);
    }

    @Autowired
    public SkillService(SkillDao skillDao, AgentSkillDao agentSkillDao, UserDao userDao,
                        SecretCrypto secretCrypto) {
        this.skillDao = skillDao;
        this.agentSkillDao = agentSkillDao;
        this.userDao = userDao;
        this.secretCrypto = secretCrypto;
    }

    public SkillVO create(CreateSkillRequest req, long tenantId, long userId) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.SKILL_NAME_REQUIRED);
        }
        if (req.getType() == null || req.getType().isBlank()) {
            throw new BizException(ErrorCode.SKILL_TYPE_REQUIRED);
        }
        rejectGenericPackagedCapabilityWrite(req.getType());
        if (skillDao.findByTypeAndName(tenantId, req.getType(), req.getName()) != null) {
            throw new BizException(ErrorCode.SKILL_DUPLICATE_NAME);
        }
        SkillDO s = new SkillDO();
        s.setTenantId(tenantId);
        s.setType(req.getType());
        s.setName(req.getName().trim());
        s.setInstallSpec(normalizeInstallSpecForStorage(req.getType(), req.getInstallSpec(), null));
        s.setDescription(req.getDescription());
        s.setSourceType("INSTALL_SPEC");
        s.setCreatorId(userId);
        s.setVersion(0);
        skillDao.insert(s);
        return toVO(s);
    }

    public SkillVO get(long id) {
        SkillDO s = skillDao.findById(id);
        if (s == null) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND);
        }
        return toVO(s);
    }

    public List<SkillVO> list(String type, int page, int size) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), 100);
        int offset = (p - 1) * sz;
        List<SkillVO> result = new ArrayList<>();
        Map<Long, String> userNameCache = new HashMap<>();
        for (SkillDO s : skillDao.list(type, offset, sz)) {
            result.add(toVO(s, userNameCache));
        }
        return result;
    }

    public SkillVO update(long id, UpdateSkillRequest req, long tenantId, long userId) {
        SkillDO s = skillDao.findById(id);
        if (s == null) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND);
        }
        rejectGenericPackagedCapabilityWrite(req.getType() != null ? req.getType() : s.getType());
        String name = req.getName() != null ? req.getName().trim() : s.getName();
        String type = req.getType() != null ? req.getType() : s.getType();
        String installSpec = req.getInstallSpec() != null ? normalizeInstallSpecForStorage(type, req.getInstallSpec(), s.getInstallSpec()) : s.getInstallSpec();
        String description = req.getDescription() != null ? req.getDescription() : s.getDescription();
        int rows = skillDao.update(id, tenantId, name, type, installSpec, description, s.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.SKILL_VERSION_CONFLICT);
        }
        return get(id);
    }

	public SkillVO createFromPackageReference(CreateSkillRequest req, PackageReference packageReference,
											 long tenantId, long userId) {
		requirePackageReference(packageReference);
		if (req.getName() == null || req.getName().isBlank()) {
			throw new BizException(ErrorCode.SKILL_NAME_REQUIRED);
		}
		if (req.getType() == null || req.getType().isBlank()) {
			throw new BizException(ErrorCode.SKILL_TYPE_REQUIRED);
		}
		rejectGenericPackagedCapabilityWrite(req.getType());
		if (skillDao.findByTypeAndName(tenantId, req.getType(), req.getName()) != null) {
			throw new BizException(ErrorCode.SKILL_DUPLICATE_NAME);
		}
		SkillDO skill = new SkillDO();
		skill.setTenantId(tenantId);
		skill.setType(req.getType());
		skill.setName(req.getName().trim());
        skill.setInstallSpec(normalizeInstallSpecForStorage(req.getType(), req.getInstallSpec(), null));
		skill.setDescription(req.getDescription());
		skill.setSourceType("OSS_ZIP");
		skill.setPackageOssRef(packageReference.ossRef());
		skill.setPackageFileName(packageReference.fileName());
		skill.setPackageSize(packageReference.size());
		skill.setPackageMd5(packageReference.md5());
		skill.setCreatorId(userId);
		skill.setVersion(0);
		skillDao.insert(skill);
		return toVO(skill);
	}

	public SkillVO updateFromPackageReference(long id, UpdateSkillRequest req, PackageReference packageReference,
											 long tenantId, long userId) {
		requirePackageReference(packageReference);
		SkillDO current = skillDao.findById(id);
		if (current == null || current.getTenantId() == null || current.getTenantId() != tenantId) {
			throw new BizException(ErrorCode.SKILL_NOT_FOUND);
		}
		String type = req.getType() == null ? current.getType() : req.getType();
		rejectGenericPackagedCapabilityWrite(type);
		String installSpec = req.getInstallSpec() == null ? current.getInstallSpec()
                : normalizeInstallSpecForStorage(type, req.getInstallSpec(), current.getInstallSpec());
		String name = req.getName() == null ? current.getName() : req.getName().trim();
		String description = req.getDescription() == null ? current.getDescription() : req.getDescription();
		int rows = skillDao.updatePackage(id, tenantId, type, installSpec, name, description,
				"OSS_ZIP", packageReference.ossRef(), packageReference.fileName(),
				packageReference.size(), packageReference.md5(), current.getVersion(), userId);
		if (rows == 0) {
			throw new BizException(ErrorCode.SKILL_VERSION_CONFLICT);
		}
		return get(id);
	}

	private void requirePackageReference(PackageReference packageReference) {
		if (packageReference == null || packageReference.ossRef() == null || packageReference.ossRef().isBlank()) {
			throw new BizException(ErrorCode.PARAM_INVALID);
		}
	}

	public record PackageReference(String ossRef, String fileName, Long size, String md5) {
	}

    public void delete(long id, long tenantId, long userId) {
        SkillDO s = skillDao.findById(id);
        if (s == null) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND);
        }
        if (agentSkillDao.countBySkillId(id, tenantId) > 0) {
            throw new BizException(ErrorCode.SKILL_DELETE_IN_USE);
        }
        int rows = skillDao.softDelete(id, tenantId, s.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.SKILL_VERSION_CONFLICT);
        }
    }

    private SkillVO toVO(SkillDO s) {
        return toVO(s, new HashMap<>());
    }

    private SkillVO toVO(SkillDO s, Map<Long, String> userNameCache) {
        SkillVO vo = new SkillVO();
        vo.setId(s.getId());
        vo.setType(s.getType());
        vo.setName(s.getName());
        vo.setInstallSpec(displayInstallSpec(s.getInstallSpec()));
        vo.setDescription(s.getDescription());
        vo.setSourceType(s.getSourceType() == null ? "INSTALL_SPEC" : s.getSourceType());
        vo.setPackageOssRef(s.getPackageOssRef());
        vo.setPackageFileName(s.getPackageFileName());
        vo.setPackageSize(s.getPackageSize());
        vo.setPackageMd5(s.getPackageMd5());
        vo.setVersion(s.getVersion());
        vo.setGmtCreate(s.getGmtCreate());
        vo.setGmtModified(s.getGmtModified());
        Long modifierId = s.getModifierId() != null ? s.getModifierId() : s.getCreatorId();
        vo.setModifierId(modifierId);
        vo.setModifierName(displayUserName(modifierId, userNameCache));
        return vo;
    }

    private String displayUserName(Long userId, Map<Long, String> userNameCache) {
        if (userId == null) {
            return null;
        }
        if (userNameCache.containsKey(userId)) {
            return userNameCache.get(userId);
        }
        UserDO user = userDao.findById(userId);
        String displayName;
        if (user == null) {
            displayName = "用户 #" + userId;
        } else if (user.getNickname() != null && !user.getNickname().isBlank()) {
            displayName = user.getNickname();
        } else if (user.getUsername() != null && !user.getUsername().isBlank()) {
            displayName = user.getUsername();
        } else {
            displayName = "用户 #" + userId;
        }
        userNameCache.put(userId, displayName);
        return displayName;
    }

    private String normalizeInstallSpecForStorage(String type, String installSpec, String previousInstallSpec) {
        if (installSpec == null) {
            return null;
        }
        String trimmed = installSpec.trim();
        if (trimmed.isEmpty()) {
            return JSON.toJSONString("");
        }
        try {
            Object parsed = JSON.parse(trimmed);
            if ("MCP".equalsIgnoreCase(type)) {
                if (!(parsed instanceof JSONObject)) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "MCP 配置必须是 JSON 对象");
                }
                return normalizeMcpConfig((JSONObject) parsed, parseMcpConfig(previousInstallSpec)).toJSONString();
            }
            return trimmed;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException ignored) {
            return JSON.toJSONString(installSpec);
        }
    }

    private JSONObject normalizeMcpConfig(JSONObject source, JSONObject previous) {
        JSONObject config = new JSONObject(true);
        config.putAll(source);
        String transport = config.getString("transport");
        if (transport == null || transport.isBlank()) {
            transport = "http";
        }
        transport = transport.trim().toLowerCase();
        if (!Set.of("http", "sse", "stdio").contains(transport)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "MCP 连接方式仅支持 HTTP、SSE 或 stdio");
        }
        config.put("transport", transport);
        if ("stdio".equals(transport)) {
            if (config.getString("command") == null || config.getString("command").isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "MCP 本地命令不能为空");
            }
            if (config.get("headers") != null || config.get("timeoutSeconds") != null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "stdio MCP 不支持请求头或超时配置");
            }
            config.put("env", normalizeEnv(config.get("env"), previous == null ? null : previous.get("env")));
            return config;
        }
        String url = config.getString("url");
        if (url == null || !url.matches("^https?://[^\\s]+$")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "MCP 地址必须是 HTTP/HTTPS URL");
        }
        config.put("url", url.trim());
        config.put("headers", normalizeHeaders(config.get("headers"), previous == null ? null : previous.get("headers")));
        Integer timeout = config.getInteger("timeoutSeconds");
        if (timeout == null) {
            timeout = 60;
        }
        if (timeout < 1 || timeout > 600) {
            throw new BizException(ErrorCode.PARAM_INVALID, "MCP 超时时间必须在 1 到 600 秒之间");
        }
        config.put("timeoutSeconds", timeout);
        return config;
    }

    private JSONObject normalizeHeaders(Object source, Object previous) {
        return normalizeValues(source, previous, "MCP Headers", HTTP_HEADER_NAME, true);
    }

    private static final Pattern ENV_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private JSONObject normalizeEnv(Object source, Object previous) {
        return normalizeValues(source, previous, "MCP Env", ENV_NAME, false);
    }

    private JSONObject normalizeValues(Object source, Object previous, String label, Pattern namePattern, boolean header) {
        JSONObject headers = new JSONObject(true);
        if (source == null) {
            return headers;
        }
        if (!(source instanceof Map)) {
            throw new BizException(ErrorCode.PARAM_INVALID, label + " 必须是键值对象");
        }
        Map<?, ?> raw = (Map<?, ?>) source;
        if (raw.size() > 32) {
            throw new BizException(ErrorCode.PARAM_INVALID, label + " 最多支持 32 项");
        }
        Set<String> names = new java.util.HashSet<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String name = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim();
            Object rawValue = entry.getValue();
            String normalizedName = name.toLowerCase();
            if (!namePattern.matcher(name).matches() || (header && RESERVED_HEADER_NAMES.contains(normalizedName))) {
                throw new BizException(ErrorCode.PARAM_INVALID, label + " 名称不合法: " + name);
            }
            if (!names.add(normalizedName)) {
                throw new BizException(ErrorCode.PARAM_INVALID, label + " 名称不能重复: " + name);
            }
            Object value = normalizeSecretValue(rawValue, previousValue(previous, name));
            if (value instanceof JSONObject) { headers.put(name, value); continue; }
            String literal = String.valueOf(value);
            if (literal.indexOf('\r') >= 0 || literal.indexOf('\n') >= 0 || literal.length() > 4096) {
                throw new BizException(ErrorCode.PARAM_INVALID, label + " 值不合法: " + name);
            }
            headers.put(name, literal);
        }
        return headers;
    }

    private Object normalizeSecretValue(Object raw, Object previous) {
        if (!(raw instanceof Map)) return raw == null ? "" : String.valueOf(raw);
        Map<?, ?> value = (Map<?, ?>) raw;
        boolean secret = Boolean.TRUE.equals(value.get("secret"))
                || "true".equalsIgnoreCase(String.valueOf(value.get("secret")))
                || "secretRef".equals(String.valueOf(value.get("kind")));
        if (!secret) return raw == null ? "" : String.valueOf(raw);
        String plain = value.get("value") == null ? "" : String.valueOf(value.get("value"));
        if (plain.isBlank()) {
            if (previous instanceof Map && "secretRef".equals(String.valueOf(((Map<?, ?>) previous).get("kind")))) {
                return previous;
            }
            throw new BizException(ErrorCode.PARAM_INVALID, "私密配置首次保存时必须填写值");
        }
        if (secretCrypto == null) throw new IllegalStateException("密文存储未配置，无法保存私密 MCP 配置");
        String ref = secretCrypto.encrypt(plain);
        if (ref == null || ref.isBlank()) throw new IllegalStateException("密文存储未返回私密配置引用");
        JSONObject stored = new JSONObject(true);
        stored.put("kind", "secretRef");
        stored.put("ref", ref);
        return stored;
    }

    private static Object previousValue(Object previous, String name) {
        if (!(previous instanceof Map)) return null;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) previous).entrySet()) {
            if (name.equalsIgnoreCase(String.valueOf(entry.getKey()))) return entry.getValue();
        }
        return null;
    }

    private static JSONObject parseMcpConfig(String installSpec) {
        if (installSpec == null || installSpec.isBlank()) return null;
        try { return JSON.parseObject(installSpec); } catch (RuntimeException ignored) { return null; }
    }

    private String displayInstallSpec(String installSpec) {
        if (installSpec == null) {
            return null;
        }
        try {
            Object parsed = JSON.parse(installSpec);
            if (parsed instanceof String) {
                return (String) parsed;
            }
            if (parsed instanceof JSONObject) return maskMcpSecrets((JSONObject) parsed).toJSONString();
        } catch (RuntimeException ignored) {
            return installSpec;
        }
        return installSpec;
    }

    private static JSONObject maskMcpSecrets(JSONObject source) {
        JSONObject copy = new JSONObject(true); copy.putAll(source);
        for (String key : List.of("headers", "env")) {
            Object raw = copy.get(key);
            if (!(raw instanceof Map)) continue;
            JSONObject values = new JSONObject(true);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Map && "secretRef".equals(String.valueOf(((Map<?, ?>) value).get("kind")))) {
                    JSONObject masked = new JSONObject(true); masked.put("kind", "secretRef"); masked.put("secret", true);
                    values.put(String.valueOf(entry.getKey()), masked);
                } else values.put(String.valueOf(entry.getKey()), value);
            }
            copy.put(key, values);
        }
        return copy;
    }

    private static void rejectGenericPackagedCapabilityWrite(String type) {
        String normalized = type == null ? "" : type.trim();
        if ("PLUGIN".equalsIgnoreCase(normalized) || "HOOK".equalsIgnoreCase(normalized)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "插件和 Runtime Hook 必须通过安装包入口配置");
        }
    }
}
