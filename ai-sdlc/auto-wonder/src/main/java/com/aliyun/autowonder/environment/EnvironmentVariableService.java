package com.aliyun.autowonder.environment;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class EnvironmentVariableService {
    private static final String MASK = "**";
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final EnvironmentVariableDao dao;
    private final SecretCrypto secretCrypto;
    private final AuditLogService auditLogService;

    public EnvironmentVariableService(EnvironmentVariableDao dao, SecretCrypto secretCrypto,
                                      AuditLogService auditLogService) {
        this.dao = dao;
        this.secretCrypto = secretCrypto;
        this.auditLogService = auditLogService;
    }

    public List<EnvironmentVariableVO> list(long tenantId) {
        return dao.listActive(tenantId).stream().map(this::toVO).toList();
    }

    @Transactional
    public EnvironmentVariableVO create(long tenantId, long userId,
                                        CreateEnvironmentVariableRequest request) {
        if (request == null || request.getValue() == null) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_VALUE_REQUIRED);
        }
        String name = validateName(request.getName());
        ensureNameAvailable(tenantId, name, null);

        EnvironmentVariableDO variable = new EnvironmentVariableDO();
        variable.setTenantId(tenantId);
        variable.setName(name);
        variable.setCredentialRef(secretCrypto.encrypt(request.getValue()));
        variable.setDescription(normalizeDescription(request.getDescription()));
        variable.setCreatorId(userId);
        variable.setModifierId(userId);
        variable.setVersion(0);
        try {
            dao.insert(variable);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_NAME_CONFLICT);
        }
        EnvironmentVariableDO persisted = requireActive(tenantId, variable.getId());
        audit(tenantId, userId, "CREATE", persisted);
        return toVO(persisted);
    }

    @Transactional
    public EnvironmentVariableVO update(long tenantId, long userId, long id,
                                        UpdateEnvironmentVariableRequest request) {
        EnvironmentVariableDO current = requireActive(tenantId, id);
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (request.isUpdateValue() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "updateValue 参数必须显式提供");
        }
        String name = validateName(request.getName());
        ensureNameAvailable(tenantId, name, id);
        String description = normalizeDescription(request.getDescription());
        int rows;
        try {
            if (Boolean.TRUE.equals(request.isUpdateValue())) {
                if (request.getValue() == null) {
                    throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_VALUE_REQUIRED);
                }
                String credentialRef = secretCrypto.encrypt(request.getValue());
                rows = dao.updateWithValue(tenantId, id, name, description, credentialRef,
                        userId, current.getVersion());
            } else {
                rows = dao.updateMetadata(tenantId, id, name, description, userId, current.getVersion());
            }
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_NAME_CONFLICT);
        }
        if (rows == 0) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_VERSION_CONFLICT);
        }
        EnvironmentVariableDO persisted = requireActive(tenantId, id);
        audit(tenantId, userId, "UPDATE", persisted);
        return toVO(persisted);
    }

    public EnvironmentVariableValueVO reveal(long tenantId, long userId, long id) {
        EnvironmentVariableDO variable = requireActive(tenantId, id);
        String value = secretCrypto.decrypt(variable.getCredentialRef());
        audit(tenantId, userId, "REVEAL", variable);
        return new EnvironmentVariableValueVO(value);
    }

    @Transactional
    public void delete(long tenantId, long userId, long id) {
        EnvironmentVariableDO variable = dao.findActiveByIdForUpdate(tenantId, id);
        if (variable == null) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_NOT_FOUND);
        }
        List<EnvironmentVariableReference> references = dao.listActiveReferences(tenantId, id);
        if (references != null && !references.isEmpty()) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_DELETE_IN_USE,
                    describeReferences(references));
        }
        if (dao.softDelete(tenantId, id, variable.getVersion(), userId) == 0) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_VERSION_CONFLICT);
        }
        audit(tenantId, userId, "DELETE", variable);
    }

    private EnvironmentVariableDO requireActive(long tenantId, long id) {
        EnvironmentVariableDO variable = dao.findActiveById(tenantId, id);
        if (variable == null) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_NOT_FOUND);
        }
        return variable;
    }

    private void ensureNameAvailable(long tenantId, String name, Long currentId) {
        EnvironmentVariableDO conflict = dao.findActiveByNameIgnoreCase(tenantId, name);
        if (conflict != null && !conflict.getId().equals(currentId)) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_NAME_CONFLICT);
        }
    }

    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.length() > 128 || !NAME_PATTERN.matcher(name).matches()) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_NAME_INVALID);
        }
        if (EnvironmentVariableNamePolicy.isReserved(name)) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_NAME_RESERVED);
        }
        return name;
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.length() > 512) {
            throw new BizException(ErrorCode.PARAM_INVALID, "环境变量说明不能超过512个字符");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private EnvironmentVariableVO toVO(EnvironmentVariableDO variable) {
        return new EnvironmentVariableVO(variable.getId(), variable.getName(), MASK,
                variable.getDescription(), variable.getGmtCreate(), variable.getGmtModified(),
                variable.getVersion());
    }

    private void audit(long tenantId, long userId, String action, EnvironmentVariableDO variable) {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(tenantId);
        record.setActorId(userId);
        record.setActorType("HUMAN");
        record.setModule("ENVIRONMENT_VARIABLE");
        record.setAction(action);
        record.setTargetType("ENVIRONMENT_VARIABLE");
        record.setTargetId(variable.getId());
        record.setTriggerType("EVENT");
        record.setTriggerSource("WEB");
        record.setEventType("ENVIRONMENT_VARIABLE_LIBRARY");
        record.detail("name", variable.getName());
        auditLogService.recordRequired(record);
    }

    private String describeReferences(List<EnvironmentVariableReference> references) {
        String detail = references.stream().map(ref -> {
            String name = ref.getAgentName() == null || ref.getAgentName().isBlank()
                    ? "数字员工" : ref.getAgentName();
            String kind = "ONLINE".equals(ref.getRefType()) ? "在线版本" : "编辑草稿";
            String version = ref.getVersionNo() == null ? "" : " v" + ref.getVersionNo();
            return name + "(#" + ref.getAgentId() + ") " + kind + version;
        }).reduce((left, right) -> left + "；" + right).orElse("");
        return "环境变量仍被数字员工引用,无法删除:" + detail
                + "。请先在对应草稿解除挂载并发布后再删除。";
    }
}
