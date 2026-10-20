package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.WorkspaceDomain;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.workspace.dto.WorkspaceRoleRequest;
import com.aliyun.autowonder.workspace.dto.WorkspaceRoleVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WorkspaceRoleService {
    private final WorkspaceRoleDao roleDao;
    private final WorkspaceDao workspaceDao;
    private final AuditLogService auditLogService;

    public WorkspaceRoleService(WorkspaceRoleDao roleDao, WorkspaceDao workspaceDao,
                                AuditLogService auditLogService) {
        this.roleDao = roleDao;
        this.workspaceDao = workspaceDao;
        this.auditLogService = auditLogService;
    }

    public List<WorkspaceRoleVO> list(long workspaceId) {
        return roleDao.listByTenant(workspaceId).stream().map(this::toVO).toList();
    }

    public WorkspaceRoleDO requireRole(long workspaceId, Long roleId) {
        WorkspaceRoleDO role = roleId == null ? null : roleDao.findById(workspaceId, roleId);
        if (role == null) throw BizException.keyed(ErrorCode.PARAM_INVALID,
                "workspace.role.not_found", null, "Role does not exist in this workspace");
        return role;
    }

    public WorkspaceRoleDO builtIn(long workspaceId, WorkspaceAccessLevel level) {
        WorkspaceRoleDO role = roleDao.findByCode(workspaceId, level.name());
        if (role == null) throw BizException.keyed(ErrorCode.CONFLICT,
                "workspace.role.migration_required", null, "Workspace roles are not initialized");
        return role;
    }

    public void seed(long workspaceId, long operatorId) {
        for (WorkspaceAccessLevel level : WorkspaceAccessLevel.values()) {
            WorkspaceRoleDO role = new WorkspaceRoleDO();
            role.setTenantId(workspaceId);
            role.setCode(level.name());
            role.setAccessLevel(level.name());
            role.setCreatorId(operatorId);
            role.setModifierId(operatorId);
            roleDao.insert(role);
        }
    }

    @Transactional
    public WorkspaceRoleVO create(long workspaceId, WorkspaceRoleRequest request, long operatorId) {
        lockWorkspace(workspaceId);
        WorkspaceRoleDO role = new WorkspaceRoleDO();
        role.setTenantId(workspaceId);
        role.setAccessLevel(WorkspaceAccessLevel.READ_WRITE.name());
        role.setCreatorId(operatorId);
        applyRequest(role, request, operatorId);
        try {
            roleDao.insert(role);
        } catch (DuplicateKeyException conflict) {
            throw duplicateName();
        }
        audit(role, operatorId, "WORKSPACE_ROLE_CREATED");
        return toVO(requireRole(workspaceId, role.getId()));
    }

    @Transactional
    public WorkspaceRoleVO update(long workspaceId, long roleId, WorkspaceRoleRequest request, long operatorId) {
        lockWorkspace(workspaceId);
        WorkspaceRoleDO role = requireRole(workspaceId, roleId);
        if (request == null || request.getVersion() == null) throw new BizException(ErrorCode.PARAM_INVALID);
        applyRequest(role, request, operatorId);
        role.setVersion(request.getVersion());
        try {
            requireUpdated(roleDao.update(role));
        } catch (DuplicateKeyException conflict) {
            throw duplicateName();
        }
        audit(role, operatorId, "WORKSPACE_ROLE_UPDATED");
        return toVO(requireRole(workspaceId, roleId));
    }

    @Transactional
    public void delete(long workspaceId, long roleId, int version, long operatorId) {
        lockWorkspace(workspaceId);
        WorkspaceRoleDO role = requireRole(workspaceId, roleId);
        requireDeletable(role);
        if (roleDao.countAssignments(workspaceId, roleId) > 0) throw BizException.keyed(
                ErrorCode.CONFLICT, "workspace.role.in_use", null, "Role is assigned to active members");
        requireUpdated(roleDao.softDelete(workspaceId, roleId, version, operatorId));
        audit(role, operatorId, "WORKSPACE_ROLE_DELETED");
    }

    private void lockWorkspace(long workspaceId) {
        if (workspaceDao.findByIdForUpdate(workspaceId) == null) throw new BizException(ErrorCode.NOT_FOUND);
    }

    private void requireDeletable(WorkspaceRoleDO role) {
        if (role.isBuiltIn()) throw BizException.keyed(ErrorCode.PARAM_INVALID,
                "workspace.role.builtin_protected", null, "Built-in roles cannot be deleted");
    }

    private void applyRequest(WorkspaceRoleDO role, WorkspaceRoleRequest request, long operatorId) {
        if (request == null || request.getReadOnlyDomains() == null
                || (!role.isBuiltIn() && (request.getName() == null || request.getName().isBlank()
                || request.getName().trim().length() > 80))) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "workspace.role.invalid", null,
                    "Custom role name must contain 1 to 80 characters and readOnlyDomains is required");
        }
        if (role.isBuiltIn()) {
            if (!WorkspaceAccessLevel.READ_WRITE.name().equals(role.getCode())) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "workspace.role.builtin_permissions_fixed", null,
                        "Viewer and administrator permissions cannot be changed");
            }
            if (request.getName() != null) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "workspace.role.builtin_name_fixed", null,
                        "Built-in role names cannot be changed");
            }
        } else {
            role.setName(request.getName().trim());
        }
        role.setReadOnlyDomains(WorkspaceDomain.toJson(WorkspaceDomain.normalize(request.getReadOnlyDomains())));
        role.setModifierId(operatorId);
    }

    private void requireUpdated(int rows) {
        if (rows != 1) throw BizException.keyed(ErrorCode.CONFLICT, "workspace.role.version_conflict", null,
                "Role changed concurrently; reload before saving");
    }

    private BizException duplicateName() {
        return BizException.keyed(ErrorCode.CONFLICT, "workspace.role.name_duplicate", null,
                "Workspace role name already exists");
    }

    private WorkspaceRoleVO toVO(WorkspaceRoleDO role) {
        WorkspaceRoleVO value = new WorkspaceRoleVO();
        value.setId(role.getId());
        value.setCode(role.getCode());
        value.setName(role.getName());
        value.setAdministrator(WorkspaceAccessLevel.ADMIN.name().equals(role.getAccessLevel()));
        value.setBuiltIn(role.isBuiltIn());
        if (value.isAdministrator()) value.setReadOnlyDomains(List.of());
        else if (WorkspaceAccessLevel.READ_ONLY.name().equals(role.getAccessLevel())) {
            value.setReadOnlyDomains(List.of(WorkspaceDomain.values()));
        } else value.setReadOnlyDomains(List.copyOf(WorkspaceDomain.fromJson(role.getReadOnlyDomains())));
        value.setVersion(role.getVersion());
        value.setMemberCount(role.getMemberCount());
        return value;
    }

    private void audit(WorkspaceRoleDO role, long operatorId, String action) {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(role.getTenantId());
        record.setActorId(operatorId);
        record.setActorType("HUMAN");
        record.setModule("ORG");
        record.setAction(action);
        record.setEventType(action);
        record.setTargetType("WORKSPACE_ROLE");
        record.setTargetId(role.getId());
        record.detail("name", role.getName()).detail("readOnlyDomains", role.getReadOnlyDomains());
        auditLogService.recordRequired(record);
    }
}
