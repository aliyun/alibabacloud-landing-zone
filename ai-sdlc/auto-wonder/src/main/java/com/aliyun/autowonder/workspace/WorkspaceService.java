package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.workspace.dto.CreateWorkspaceRequest;
import com.aliyun.autowonder.workspace.dto.CurrentMembershipVO;
import com.aliyun.autowonder.workspace.dto.MemberCandidateVO;
import com.aliyun.autowonder.workspace.dto.MemberVO;
import com.aliyun.autowonder.workspace.dto.WorkspaceVO;
import com.aliyun.autowonder.workspace.dto.SwitchWorkspaceResponse;
import com.aliyun.autowonder.statemachine.StatusTemplateSeeder;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class WorkspaceService {
    private static final int MEMBER_CANDIDATE_LIMIT = 20;
    private static final String AUDIT_ACTOR_HUMAN = "HUMAN";
    private static final String AUDIT_MODULE_ORG = "ORG";

    private final WorkspaceDao workspaceDao;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final StatusTemplateSeeder statusTemplateSeeder;
    private final JwtService jwtService;
    private final UserDao userDao;
    private final AuditLogService auditLogService;

    public WorkspaceService(WorkspaceDao workspaceDao, WorkspaceMemberDao workspaceMemberDao,
                      StatusTemplateSeeder statusTemplateSeeder, JwtService jwtService,
                      UserDao userDao, AuditLogService auditLogService) {
        this.workspaceDao = workspaceDao;
        this.workspaceMemberDao = workspaceMemberDao;
        this.statusTemplateSeeder = statusTemplateSeeder;
        this.jwtService = jwtService;
        this.userDao = userDao;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public WorkspaceVO create(CreateWorkspaceRequest req, long ownerUserId) {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.WORKSPACE_NAME_REQUIRED);
        }
        String trimmedName = req.getName().trim();
        if (workspaceDao.findByName(trimmedName) != null) {
            throw new BizException(ErrorCode.WORKSPACE_NAME_DUPLICATE);
        }

        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setName(trimmedName);
        workspace.setDescription(req.getDescription());
        workspace.setBackground(req.getBackground());
        workspace.setOwnerId(ownerUserId);
        workspace.setStatus(0);
        workspace.setCreatorId(ownerUserId);
        workspaceDao.insert(workspace);

        WorkspaceMemberDO owner = new WorkspaceMemberDO();
        owner.setTenantId(workspace.getId());
        owner.setUserId(ownerUserId);
        owner.setStatus(0);
        owner.setAccessLevel(WorkspaceAccessLevel.ADMIN.name());
        owner.setIdentityTags(IdentityTags.toJson(List.of()));
        owner.setCreatorId(ownerUserId);
        workspaceMemberDao.insert(owner);

        statusTemplateSeeder.seed(workspace.getId(), ownerUserId);

        WorkspaceVO result = new WorkspaceVO();
        result.setId(workspace.getId());
        result.setName(workspace.getName());
        result.setDescription(workspace.getDescription());
        return result;
    }

    public List<WorkspaceVO> listByUser(long userId) {
        List<WorkspaceVO> result = new ArrayList<>();
        for (WorkspaceDO workspace : workspaceDao.listByUser(userId)) {
            result.add(toVO(workspace));
        }
        return result;
    }

    public List<WorkspaceVO> listByUserWithAccess(long userId) {
        List<WorkspaceVO> result = new ArrayList<>();
        for (WorkspaceMembershipDO membership : workspaceDao.listMembershipsByUser(userId)) {
            WorkspaceVO value = new WorkspaceVO();
            value.setId(membership.getId());
            value.setName(membership.getName());
            value.setDescription(membership.getDescription());
            value.setAccessLevel(exactAccessLevel(membership.getAccessLevel()));
            result.add(value);
        }
        return result;
    }

    public WorkspaceAccessLevel activeAccessLevel(long workspaceId, long userId) {
        WorkspaceMemberDO member = requireActiveMember(
                workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId));
        return exactAccessLevel(member.getAccessLevel());
    }

    public WorkspaceVO scopedWorkspace(long workspaceId, WorkspaceAccessLevel accessLevel) {
        WorkspaceVO result = getCurrent(workspaceId);
        result.setAccessLevel(accessLevel);
        return result;
    }

    public WorkspaceVO getCurrent(long workspaceId) {
        WorkspaceDO workspace = workspaceDao.findById(workspaceId);
        if (workspace == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作空间不存在");
        }
        return toVO(workspace);
    }

    private WorkspaceVO toVO(WorkspaceDO workspace) {
        WorkspaceVO value = new WorkspaceVO();
        value.setId(workspace.getId());
        value.setName(workspace.getName());
        value.setDescription(workspace.getDescription());
        return value;
    }

    public SwitchWorkspaceResponse switchWorkspace(long workspaceId, long userId) {
        WorkspaceMemberDO member = requireActiveMember(workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId));
        WorkspaceAccessLevel accessLevel = exactAccessLevel(member.getAccessLevel());

        AutoWonderContext context = AutoWonderContext.get();
        context.setCurrentWorkspaceId(workspaceId);
        context.setWorkspaceAccessLevel(accessLevel);

        String accessToken = jwtService.signAccess(
                new TokenPayload(userId, workspaceId, UUID.randomUUID().toString()));
        return new SwitchWorkspaceResponse(accessToken, accessLevel);
    }

    public List<MemberVO> listMembers(long workspaceId) {
        WorkspaceDO workspace = workspaceDao.findById(workspaceId);
        List<MemberVO> result = new ArrayList<>();
        for (WorkspaceMemberDO member : workspaceMemberDao.listByTenant(workspaceId)) {
            MemberVO value = new MemberVO();
            value.setUserId(member.getUserId());
            value.setJoinedAt(member.getJoinedAt());
            value.setOwner(workspace != null && Objects.equals(workspace.getOwnerId(), member.getUserId()));
            value.setAccessLevel(exactAccessLevel(member.getAccessLevel()));
            value.setIdentityTags(IdentityTags.fromJson(member.getIdentityTags()));
            applyUserIdentity(value, userDao.findById(member.getUserId()));
            result.add(value);
        }
        return result;
    }

    public CurrentMembershipVO currentMembership(long workspaceId, long userId) {
        WorkspaceMemberDO member = currentRequestMember(workspaceId, userId);
        WorkspaceDO workspace = workspaceDao.findById(workspaceId);
        CurrentMembershipVO result = new CurrentMembershipVO();
        result.setUserId(member.getUserId());
        result.setJoinedAt(member.getJoinedAt());
        result.setOwner(workspace != null && Objects.equals(workspace.getOwnerId(), member.getUserId()));
        result.setAccessLevel(exactAccessLevel(member.getAccessLevel()));
        result.setIdentityTags(IdentityTags.fromJson(member.getIdentityTags()));
        applyUserIdentity(result, userDao.findById(member.getUserId()));
        return result;
    }

    private WorkspaceMemberDO currentRequestMember(long workspaceId, long userId) {
        AutoWonderContext context = AutoWonderContext.get();
        WorkspaceMemberDO member = context.getWorkspaceMember();
        if (Objects.equals(context.getCurrentWorkspaceId(), workspaceId)
                && Objects.equals(context.getUserId(), userId)
                && member != null
                && Objects.equals(member.getTenantId(), workspaceId)
                && Objects.equals(member.getUserId(), userId)) {
            return requireActiveMember(member);
        }
        return requireActiveMember(workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId));
    }

    public List<MemberCandidateVO> searchMemberCandidates(long workspaceId, String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        List<UserDO> users = userDao.searchWorkspaceCandidates(
                workspaceId, normalizedKeyword, MEMBER_CANDIDATE_LIMIT);
        List<MemberCandidateVO> result = new ArrayList<>();
        for (UserDO user : users) {
            MemberCandidateVO value = new MemberCandidateVO();
            value.setUserId(user.getId());
            value.setUsername(user.getUsername());
            value.setEmail(user.getEmail());
            value.setNickname(user.getNickname());
            result.add(value);
        }
        return result;
    }

    @Transactional
    public void addMember(long workspaceId, Long targetUserId, long operatorId) {
        if (targetUserId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户不能为空");
        }
        UserDO targetUser = userDao.findById(targetUserId);
        if (targetUser == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        WorkspaceMemberDO existing = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, targetUserId);
        if (isActiveMember(existing)) {
            return;
        }
        if (!Integer.valueOf(0).equals(targetUser.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户不可添加");
        }

        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setTenantId(workspaceId);
        member.setUserId(targetUserId);
        member.setStatus(0);
        member.setAccessLevel(WorkspaceAccessLevel.READ_ONLY.name());
        member.setIdentityTags(IdentityTags.toJson(List.of()));
        member.setCreatorId(operatorId);
        member.setModifierId(operatorId);
        workspaceMemberDao.insertOrActivate(member);
    }

    @Transactional
    public void updateMemberAccess(long workspaceId, long targetUserId,
                                   WorkspaceAccessLevel requestedLevel, long operatorId) {
        if (requestedLevel == null) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID);
        }
        if (targetUserId == operatorId) {
            throw new BizException(ErrorCode.WORKSPACE_SELF_LEVEL_MUTATION_FORBIDDEN);
        }
        WorkspaceDO workspace = workspaceDao.findByIdForUpdate(workspaceId);
        if (workspace != null && Objects.equals(workspace.getOwnerId(), targetUserId)) {
            throw new BizException(ErrorCode.WORKSPACE_OWNER_MUTATION_PROTECTED);
        }

        WorkspaceMemberDO target = requireActiveMember(
                workspaceMemberDao.findByWorkspaceAndUserForUpdate(workspaceId, targetUserId));
        WorkspaceAccessLevel oldLevel = exactAccessLevel(target.getAccessLevel());
        int updated = workspaceMemberDao.updateAccessLevel(
                workspaceId, targetUserId, requestedLevel.name(), operatorId);
        requireSingleUpdate(updated);

        AuditLogRecord audit = memberAudit(
                workspaceId, operatorId, targetUserId, "MEMBER_ACCESS_CHANGED");
        audit.detail("oldAccessLevel", oldLevel.name())
                .detail("newAccessLevel", requestedLevel.name())
                .detail("operatorId", operatorId)
                .detail("targetUserId", targetUserId);
        auditLogService.recordRequired(audit);
    }

    @Transactional
    public void updateMemberIdentityTags(long workspaceId, long targetUserId,
                                         List<String> requestedTags, long operatorId) {
        WorkspaceMemberDO target = requireActiveMember(
                workspaceMemberDao.findByWorkspaceAndUserForUpdate(workspaceId, targetUserId));
        List<String> oldTags = IdentityTags.fromJson(target.getIdentityTags());
        List<String> newTags = IdentityTags.normalize(requestedTags);
        int updated = workspaceMemberDao.updateIdentityTags(
                workspaceId, targetUserId, IdentityTags.toJson(newTags), operatorId);
        requireSingleUpdate(updated);

        AuditLogRecord audit = memberAudit(
                workspaceId, operatorId, targetUserId, "MEMBER_IDENTITY_TAGS_CHANGED");
        audit.detail("oldIdentityTags", oldTags)
                .detail("newIdentityTags", newTags)
                .detail("operatorId", operatorId)
                .detail("targetUserId", targetUserId);
        auditLogService.recordRequired(audit);
    }

    @Transactional
    public void removeMember(long workspaceId, long targetUserId, long operatorId) {
        WorkspaceDO workspace = workspaceDao.findByIdForUpdate(workspaceId);
        if (workspace != null && Objects.equals(workspace.getOwnerId(), targetUserId)) {
            throw new BizException(ErrorCode.WORKSPACE_OWNER_MUTATION_PROTECTED);
        }
        requireActiveMember(workspaceMemberDao.findByWorkspaceAndUserForUpdate(workspaceId, targetUserId));
        requireSingleUpdate(workspaceMemberDao.softDelete(workspaceId, targetUserId, operatorId));
    }

    @Transactional
    public void transferOwner(long workspaceId, long targetUserId, long operatorId) {
        WorkspaceDO workspace = workspaceDao.findByIdForUpdate(workspaceId);
        if (workspace == null
                || !Objects.equals(workspace.getOwnerId(), operatorId)
                || targetUserId == operatorId) {
            throw ownerTransferInvalid();
        }

        WorkspaceMemberDO currentOwner = workspaceMemberDao.findByWorkspaceAndUserForUpdate(workspaceId, operatorId);
        WorkspaceMemberDO target = workspaceMemberDao.findByWorkspaceAndUserForUpdate(workspaceId, targetUserId);
        if (!isActiveMember(currentOwner)
                || !isExactLevel(currentOwner, WorkspaceAccessLevel.ADMIN)
                || !isActiveMember(target)) {
            throw ownerTransferInvalid();
        }

        WorkspaceAccessLevel targetLevel;
        try {
            targetLevel = exactAccessLevel(target.getAccessLevel());
        } catch (BizException exception) {
            throw ownerTransferInvalid();
        }
        if (targetLevel != WorkspaceAccessLevel.ADMIN) {
            int promoted = workspaceMemberDao.updateAccessLevel(
                    workspaceId, targetUserId, WorkspaceAccessLevel.ADMIN.name(), operatorId);
            if (promoted != 1) {
                throw ownerTransferInvalid();
            }
        }

        int ownerUpdated = workspaceDao.updateOwner(
                workspaceId, operatorId, targetUserId, operatorId);
        if (ownerUpdated != 1) {
            throw ownerTransferInvalid();
        }

        AuditLogRecord audit = audit(
                workspaceId, operatorId, "ORG_OWNER_TRANSFERRED", "ORG", workspaceId);
        audit.detail("oldOwnerId", operatorId)
                .detail("newOwnerId", targetUserId)
                .detail("operatorId", operatorId)
                .detail("targetUserId", targetUserId);
        auditLogService.recordRequired(audit);
    }

    private WorkspaceMemberDO requireActiveMember(WorkspaceMemberDO member) {
        if (!isActiveMember(member)) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return member;
    }

    private boolean isActiveMember(WorkspaceMemberDO member) {
        return member != null
                && Integer.valueOf(0).equals(member.getStatus())
                && Integer.valueOf(0).equals(member.getIsDeleted());
    }

    private WorkspaceAccessLevel exactAccessLevel(String persistedLevel) {
        try {
            return WorkspaceAccessLevel.valueOf(persistedLevel);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID);
        }
    }

    private boolean isExactLevel(WorkspaceMemberDO member, WorkspaceAccessLevel expected) {
        try {
            return exactAccessLevel(member.getAccessLevel()) == expected;
        } catch (BizException exception) {
            return false;
        }
    }

    private void requireSingleUpdate(int updated) {
        if (updated != 1) {
            throw new BizException(ErrorCode.CONFLICT);
        }
    }

    private BizException ownerTransferInvalid() {
        return new BizException(ErrorCode.WORKSPACE_OWNER_TRANSFER_INVALID);
    }

    private AuditLogRecord memberAudit(long workspaceId, long operatorId,
                                       long targetUserId, String event) {
        return audit(workspaceId, operatorId, event, "MEMBER", targetUserId);
    }

    private AuditLogRecord audit(long workspaceId, long operatorId, String event,
                                 String targetType, long targetId) {
        AuditLogRecord audit = new AuditLogRecord();
        audit.setTenantId(workspaceId);
        audit.setActorId(operatorId);
        audit.setActorType(AUDIT_ACTOR_HUMAN);
        audit.setModule(AUDIT_MODULE_ORG);
        audit.setAction(event);
        audit.setEventType(event);
        audit.setTargetType(targetType);
        audit.setTargetId(targetId);
        return audit;
    }

    private void applyUserIdentity(MemberVO target, UserDO user) {
        if (user == null) {
            return;
        }
        target.setUsername(user.getUsername());
        target.setEmail(user.getEmail());
        target.setNickname(user.getNickname());
    }

    private void applyUserIdentity(CurrentMembershipVO target, UserDO user) {
        if (user == null) {
            return;
        }
        target.setUsername(user.getUsername());
        target.setEmail(user.getEmail());
        target.setNickname(user.getNickname());
    }
}
