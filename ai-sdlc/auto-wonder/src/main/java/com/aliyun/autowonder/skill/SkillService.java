package com.aliyun.autowonder.skill;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.dto.CreateSkillRequest;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.skill.dto.UpdateSkillRequest;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SkillService {

    private final SkillDao skillDao;
    private final AgentSkillDao agentSkillDao;
    private final UserDao userDao;

    public SkillService(SkillDao skillDao, AgentSkillDao agentSkillDao, UserDao userDao) {
        this.skillDao = skillDao;
        this.agentSkillDao = agentSkillDao;
        this.userDao = userDao;
    }

    public SkillVO create(CreateSkillRequest req, long tenantId, long userId) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.SKILL_NAME_REQUIRED);
        }
        if (req.getType() == null || req.getType().isBlank()) {
            throw new BizException(ErrorCode.SKILL_TYPE_REQUIRED);
        }
        rejectGenericPluginWrite(req.getType());
        if (skillDao.findByTypeAndName(tenantId, req.getType(), req.getName()) != null) {
            throw new BizException(ErrorCode.SKILL_DUPLICATE_NAME);
        }
        SkillDO s = new SkillDO();
        s.setTenantId(tenantId);
        s.setType(req.getType());
        s.setName(req.getName().trim());
        s.setInstallSpec(normalizeInstallSpecForStorage(req.getInstallSpec()));
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
        rejectGenericPluginWrite(req.getType() != null ? req.getType() : s.getType());
        String name = req.getName() != null ? req.getName().trim() : s.getName();
        String type = req.getType() != null ? req.getType() : s.getType();
        String installSpec = req.getInstallSpec() != null ? normalizeInstallSpecForStorage(req.getInstallSpec()) : s.getInstallSpec();
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
		rejectGenericPluginWrite(req.getType());
		if (skillDao.findByTypeAndName(tenantId, req.getType(), req.getName()) != null) {
			throw new BizException(ErrorCode.SKILL_DUPLICATE_NAME);
		}
		SkillDO skill = new SkillDO();
		skill.setTenantId(tenantId);
		skill.setType(req.getType());
		skill.setName(req.getName().trim());
		skill.setInstallSpec(normalizeInstallSpecForStorage(req.getInstallSpec()));
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
		rejectGenericPluginWrite(type);
		String installSpec = req.getInstallSpec() == null ? current.getInstallSpec()
				: normalizeInstallSpecForStorage(req.getInstallSpec());
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

    private String normalizeInstallSpecForStorage(String installSpec) {
        if (installSpec == null) {
            return null;
        }
        String trimmed = installSpec.trim();
        if (trimmed.isEmpty()) {
            return JSON.toJSONString("");
        }
        try {
            JSON.parse(trimmed);
            return trimmed;
        } catch (RuntimeException ignored) {
            return JSON.toJSONString(installSpec);
        }
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
        } catch (RuntimeException ignored) {
            return installSpec;
        }
        return installSpec;
    }

    private static void rejectGenericPluginWrite(String type) {
        if ("PLUGIN".equalsIgnoreCase(type == null ? "" : type.trim())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "插件必须通过安装包入口配置 ZIP 和 Provider");
        }
    }
}
