package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.workspace.dto.CreateWorkspaceRequest;
import com.aliyun.autowonder.workspace.dto.CurrentMembershipVO;
import com.aliyun.autowonder.workspace.dto.MemberCandidateVO;
import com.aliyun.autowonder.workspace.dto.MemberVO;
import com.aliyun.autowonder.workspace.dto.RecycleBinItemVO;
import com.aliyun.autowonder.workspace.dto.RestoreWorkspaceRequest;
import com.aliyun.autowonder.workspace.dto.WorkspaceUpdateRequest;
import com.aliyun.autowonder.workspace.dto.WorkspaceVO;
import com.aliyun.autowonder.workspace.dto.SwitchWorkspaceResponse;
import com.aliyun.autowonder.agent.PlatformAgentSeeder;
import com.aliyun.autowonder.workspace.event.WorkspaceDeletedEvent;
import com.aliyun.autowonder.statemachine.StatusTemplateSeeder;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkspaceService {
    private static final int MEMBER_CANDIDATE_LIMIT = 20;
    private static final String AUDIT_ACTOR_HUMAN = "HUMAN";
    private static final String AUDIT_MODULE_ORG = "ORG";

    /** org.name is VARCHAR(128); rejecting longer input beats a database truncation error. */
    private static final int NAME_MAX_LENGTH = 128;
    /** org.description is VARCHAR(512). */
    private static final int DESCRIPTION_MAX_LENGTH = 512;
    private static final int RECYCLE_BIN_MAX_PAGE_SIZE = 100;

    private final WorkspaceDao workspaceDao;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final StatusTemplateSeeder statusTemplateSeeder;
    private final PlatformAgentSeeder platformAgentSeeder;
    private final JwtService jwtService;
    private final UserDao userDao;
    private final AuditLogService auditLogService;
    private final SystemAdminService systemAdminService;
    private final WorkspaceDeletionLinkage deletionLinkage;
    private final ApplicationEventPublisher eventPublisher;

    public WorkspaceService(WorkspaceDao workspaceDao, WorkspaceMemberDao workspaceMemberDao,
                      StatusTemplateSeeder statusTemplateSeeder,
                      PlatformAgentSeeder platformAgentSeeder, JwtService jwtService,
                      UserDao userDao, AuditLogService auditLogService,
                      SystemAdminService systemAdminService,
                      WorkspaceDeletionLinkage deletionLinkage,
                      ApplicationEventPublisher eventPublisher) {
        this.workspaceDao = workspaceDao;
        this.workspaceMemberDao = workspaceMemberDao;
        this.statusTemplateSeeder = statusTemplateSeeder;
        this.platformAgentSeeder = platformAgentSeeder;
        this.jwtService = jwtService;
        this.userDao = userDao;
        this.auditLogService = auditLogService;
        this.systemAdminService = systemAdminService;
        this.deletionLinkage = deletionLinkage;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public WorkspaceVO create(CreateWorkspaceRequest req, long ownerUserId) {
        if (req == null) {
            throw new BizException(ErrorCode.WORKSPACE_NAME_REQUIRED);
        }
        String trimmedName = requireName(req.getName());
        if (workspaceDao.findByName(trimmedName) != null) {
            throw new BizException(ErrorCode.WORKSPACE_NAME_DUPLICATE);
        }

        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setName(trimmedName);
        // D2: the unique key is active_name_key, not name, so a row written without it would never
        // collide and two workspaces could share a name.
        workspace.setActiveNameKey(trimmedName);
        workspace.setDescription(normalizeDescription(req.getDescription()));
        workspace.setBackground(normalizeBackground(req.getBackground()));
        workspace.setOwnerId(ownerUserId);
        workspace.setStatus(0);
        workspace.setCreatorId(ownerUserId);
        workspace.setVersion(0);
        try {
            workspaceDao.insert(workspace);
        } catch (DuplicateKeyException race) {
            // Two concurrent creates of the same name: uk_active_name lets exactly one through and
            // the loser surfaces the same business error the pre-check would have.
            throw new BizException(ErrorCode.WORKSPACE_NAME_DUPLICATE);
        }

        WorkspaceMemberDO owner = new WorkspaceMemberDO();
        owner.setTenantId(workspace.getId());
        owner.setUserId(ownerUserId);
        owner.setStatus(0);
        owner.setAccessLevel(WorkspaceAccessLevel.ADMIN.name());
        owner.setIdentityTags(IdentityTags.toJson(List.of()));
        owner.setCreatorId(ownerUserId);
        workspaceMemberDao.insert(owner);

        statusTemplateSeeder.seed(workspace.getId(), ownerUserId);
        platformAgentSeeder.seed(workspace.getId(), ownerUserId);

        WorkspaceVO result = toVO(workspace);
        result.setAccessLevel(WorkspaceAccessLevel.ADMIN);
        result.setIsOwner(true);
        result.setCanManage(true);
        return result;
    }

    public List<WorkspaceVO> listByUser(long userId) {
        // Levels come from one bulk membership read rather than a per-card query: the card renders
        // its edit/delete entries from canManage, and the edit modal needs version + background,
        // which only the full org row carries.
        Map<Long, String> levelByWorkspaceId = new HashMap<>();
        for (WorkspaceMembershipDO membership : workspaceDao.listMembershipsByUser(userId)) {
            levelByWorkspaceId.put(membership.getId(), membership.getAccessLevel());
        }
        List<WorkspaceVO> result = new ArrayList<>();
        for (WorkspaceDO workspace : workspaceDao.listByUser(userId)) {
            WorkspaceVO value = toVO(workspace);
            WorkspaceAccessLevel accessLevel =
                    exactAccessLevel(levelByWorkspaceId.get(workspace.getId()));
            value.setAccessLevel(accessLevel);
            // D8: ownership is org.owner_id — there is no OWNER access level to compare against.
            boolean owner = Objects.equals(workspace.getOwnerId(), userId);
            value.setIsOwner(owner);
            value.setCanManage(owner || accessLevel == WorkspaceAccessLevel.ADMIN);
            result.add(value);
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
            WorkspaceAccessLevel accessLevel = exactAccessLevel(membership.getAccessLevel());
            value.setAccessLevel(accessLevel);
            // F8: the card renders edit/delete from these two flags, computed from the same single
            // join, so no per-card permission round trip is needed.
            boolean owner = Objects.equals(membership.getOwnerId(), userId);
            value.setIsOwner(owner);
            value.setCanManage(owner || accessLevel == WorkspaceAccessLevel.ADMIN);
            result.add(value);
        }
        return result;
    }

    public WorkspaceAccessLevel activeAccessLevel(long workspaceId, long userId) {
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId);
        if (isActiveMember(member)) {
            return exactAccessLevel(member.getAccessLevel());
        }
        // Shared by MCP personal tokens: the platform-admin branch keeps page and MCP calls
        // by the same user on the same authorization rule. Machine credentials are resolved
        // from their token level instead and never gain cross-workspace access here.
        if (systemAdminService.isSystemAdmin(userId)) {
            return WorkspaceAccessLevel.ADMIN;
        }
        throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
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
        value.setBackground(workspace.getBackground());
        value.setVersion(workspace.getVersion());
        return value;
    }

    public SwitchWorkspaceResponse switchWorkspace(long workspaceId, long userId) {
        // The same usability predicate AuthFilter applies on every request: a deleted or
        // disabled workspace must be rejected before a token is issued, not on the first
        // request that token carries.
        if (workspaceDao.countUsable(workspaceId) == 0) {
            throw new BizException(ErrorCode.ORG_DELETED_OR_DISABLED);
        }
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId);
        WorkspaceAccessLevel accessLevel;
        if (isActiveMember(member)) {
            accessLevel = exactAccessLevel(member.getAccessLevel());
        } else if (systemAdminService.isSystemAdmin(userId)) {
            // A platform admin may enter any workspace without being a member. ADMIN is the
            // highest level, so every @RequireWorkspaceAccess guard treats the session as a
            // workspace admin; the flag is re-read from the database on every request by
            // AuthFilter, so a revoked admin cannot keep using an access token issued here.
            accessLevel = WorkspaceAccessLevel.ADMIN;
        } else {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }

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
        if (!isActiveMember(member) && systemAdminService.isSystemAdmin(userId)) {
            // A platform admin entered a workspace it never joined: AuthFilter already grants
            // ADMIN, and this synthetic membership keeps the frontend flags consistent with it.
            WorkspaceDO workspace = workspaceDao.findById(workspaceId);
            CurrentMembershipVO result = new CurrentMembershipVO();
            result.setUserId(userId);
            result.setJoinedAt(null);
            result.setOwner(workspace != null && Objects.equals(workspace.getOwnerId(), userId));
            result.setAccessLevel(WorkspaceAccessLevel.ADMIN);
            result.setIdentityTags(IdentityTags.fromJson(null));
            applyUserIdentity(result, userDao.findById(userId));
            return result;
        }
        member = requireActiveMember(member);
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

    /**
     * Prefers the member AuthFilter already validated for this very request over an identical
     * second DAO read; the DAO fallback covers callers without a request context. Activeness is
     * left to {@link #currentMembership}, whose platform-admin branch must see the raw member.
     */
    private WorkspaceMemberDO currentRequestMember(long workspaceId, long userId) {
        AutoWonderContext context = AutoWonderContext.get();
        WorkspaceMemberDO member = context.getWorkspaceMember();
        if (Objects.equals(context.getCurrentWorkspaceId(), workspaceId)
                && Objects.equals(context.getUserId(), userId)
                && member != null
                && Objects.equals(member.getTenantId(), workspaceId)
                && Objects.equals(member.getUserId(), userId)) {
            return member;
        }
        return workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId);
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

    /** F1: edit name/description/background under an optimistic lock. Owner or ADMIN only. */
    @Transactional
    public WorkspaceVO updateWorkspace(long workspaceId, WorkspaceUpdateRequest req, long operatorId) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        if (req.getVersion() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "version 不能为空");
        }
        WorkspaceDO workspace = requireManageableWorkspace(workspaceId, operatorId);
        String name = requireName(req.getName());
        String description = normalizeDescription(req.getDescription());
        String background = normalizeBackground(req.getBackground());
        // excludeId is this row, so saving an untouched name is not reported as a duplicate.
        if (workspaceDao.countActiveByName(name, workspaceId) > 0) {
            throw new BizException(ErrorCode.WORKSPACE_NAME_DUPLICATE);
        }
        int updated = workspaceDao.updateDetail(workspaceId, name, name, description, background,
                req.getVersion(), operatorId);
        if (updated != 1) {
            // The row was locked above, so a miss means the client's version went stale: somebody
            // else saved between the modal opening and this submit.
            throw new BizException(ErrorCode.ORG_VERSION_CONFLICT);
        }

        AuditLogRecord audit = audit(workspaceId, operatorId, "ORG_UPDATED", "ORG", workspaceId);
        audit.detail("oldName", workspace.getName())
                .detail("newName", name)
                .detail("operatorId", operatorId);
        auditLogService.recordRequired(audit);

        WorkspaceVO result = toVO(workspace);
        result.setName(name);
        result.setDescription(description);
        result.setBackground(background);
        result.setVersion(req.getVersion() + 1);
        applyManageFlags(result, workspace, operatorId);
        return result;
    }

    /**
     * F2: logical delete. Members and business data are kept, the name is released in the same
     * statement, and ACTIVE scheduled tasks are paused inside this transaction (D5). The dispatch
     * half is published as an event because it calls remote executors, which must not happen while
     * row locks are held.
     */
    @Transactional
    public WorkspaceVO deleteWorkspace(long workspaceId, long operatorId) {
        WorkspaceDO workspace = requireManageableWorkspace(workspaceId, operatorId);
        int deleted = workspaceDao.softDelete(workspaceId, operatorId);
        if (deleted != 1) {
            throw new BizException(ErrorCode.CONFLICT, "工作空间已被删除");
        }
        int pausedTasks = deletionLinkage.pauseScheduledTasks(workspaceId, operatorId);

        AuditLogRecord audit = audit(workspaceId, operatorId, "ORG_DELETED", "ORG", workspaceId);
        audit.detail("name", workspace.getName())
                .detail("operatorId", operatorId)
                // scheduled_task has no reason column, so the mandated pause reason is kept here.
                .detail("reason", WorkspaceDeletionLinkage.DELETION_REASON)
                .detail("pausedScheduledTasks", pausedTasks);
        auditLogService.recordRequired(audit);

        eventPublisher.publishEvent(
                new WorkspaceDeletedEvent(workspaceId, workspace.getName(), operatorId));

        WorkspaceVO result = toVO(workspace);
        applyManageFlags(result, workspace, operatorId);
        return result;
    }

    /**
     * F4: recycle bin. Visibility — original Owner, original effective ADMIN, or platform admin —
     * is decided entirely in SQL, because filtering in memory after the query would both leak the
     * existence of ids the caller may not see and desynchronize the page from its total.
     */
    public PageResult<RecycleBinItemVO> pageRecycleBin(String keyword, int page, int size, long operatorId) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), RECYCLE_BIN_MAX_PAGE_SIZE);
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        boolean systemAdmin = systemAdminService.isSystemAdmin(operatorId);
        int offset = (normalizedPage - 1) * normalizedSize;

        List<WorkspaceDO> rows = workspaceDao.pageRecycleBin(
                operatorId, systemAdmin, normalizedKeyword, offset, normalizedSize);
        long total = workspaceDao.countRecycleBin(operatorId, systemAdmin, normalizedKeyword);

        Set<String> takenNames = takenActiveNames(rows);
        Map<Long, String> userNames = userNames(rows);

        List<RecycleBinItemVO> items = new ArrayList<>(rows.size());
        for (WorkspaceDO row : rows) {
            RecycleBinItemVO item = new RecycleBinItemVO();
            item.setId(row.getId());
            item.setName(row.getName());
            item.setDescription(row.getDescription());
            item.setOwnerId(row.getOwnerId());
            item.setOwnerName(userNames.get(row.getOwnerId()));
            item.setDeletedAt(row.getDeletedAt());
            item.setDeletedBy(row.getDeletedBy());
            item.setDeletedByName(userNames.get(row.getDeletedBy()));
            // A display hint only; the identity filtering above already decided which rows exist.
            item.setRestorable(!takenNames.contains(row.getName()));
            items.add(item);
        }
        return new PageResult<>(items, total, normalizedPage, normalizedSize);
    }

    /**
     * F5: restore. Deliberately never consults the deleted workspace's own token context — the
     * caller's token points at some other workspace by now — and decides permission from the stored
     * owner_id plus the member rows that logical delete preserved.
     */
    @Transactional
    public WorkspaceVO restoreWorkspace(long workspaceId, RestoreWorkspaceRequest req, long operatorId) {
        WorkspaceDO workspace = workspaceDao.findByIdAnyState(workspaceId);
        // One code for "no such workspace" and "not yours": distinguishing them would let a caller
        // enumerate other tenants' deleted workspaces by guessing ids.
        if (workspace == null || !canManageDeleted(workspace, operatorId)) {
            throw new BizException(ErrorCode.ORG_NOT_FOUND_OR_NO_PERMISSION);
        }
        if (!isDeleted(workspace)) {
            // Duplicate or concurrent restore: the wanted end state already holds, and only one
            // state change ever happens because the UPDATE is conditional on is_deleted = 1.
            return restoredView(workspace, operatorId);
        }
        // D4: rename-on-restore, so a name taken since the delete can be resolved in one request.
        String requestedName = req == null ? null : req.getNewName();
        String name = requestedName == null || requestedName.isBlank()
                ? requireName(workspace.getName())
                : requireName(requestedName);
        if (workspaceDao.countActiveByName(name, null) > 0) {
            throw new BizException(ErrorCode.ORG_RESTORE_NAME_CONFLICT);
        }
        int restored = workspaceDao.restore(workspaceId, name, name, operatorId);
        if (restored != 1) {
            WorkspaceDO current = workspaceDao.findByIdAnyState(workspaceId);
            if (current == null || isDeleted(current)) {
                throw new BizException(ErrorCode.ORG_NOT_FOUND_OR_NO_PERMISSION);
            }
            return restoredView(current, operatorId);
        }

        AuditLogRecord audit = audit(workspaceId, operatorId, "ORG_RESTORED", "ORG", workspaceId);
        audit.detail("name", name)
                .detail("deletedName", workspace.getName())
                .detail("operatorId", operatorId)
                // D6: restore leaves scheduled tasks PAUSED for a human to re-enable on purpose.
                .detail("scheduledTasksResumed", false);
        auditLogService.recordRequired(audit);

        WorkspaceVO result = toVO(workspace);
        result.setName(name);
        result.setVersion(workspace.getVersion() == null ? 1 : workspace.getVersion() + 1);
        applyManageFlags(result, workspace, operatorId);
        return result;
    }

    private WorkspaceVO restoredView(WorkspaceDO workspace, long operatorId) {
        WorkspaceVO result = toVO(workspace);
        applyManageFlags(result, workspace, operatorId);
        return result;
    }

    private WorkspaceDO requireManageableWorkspace(long workspaceId, long operatorId) {
        WorkspaceDO workspace = workspaceDao.findByIdForUpdate(workspaceId);
        if (workspace == null || !canManage(workspace, operatorId)) {
            throw new BizException(ErrorCode.ORG_NOT_FOUND_OR_NO_PERMISSION);
        }
        return workspace;
    }

    private boolean canManage(WorkspaceDO workspace, long operatorId) {
        // D8: ownership is org.owner_id — there is no OWNER access level to compare against.
        // A platform admin manages every workspace without being a member of it.
        return Objects.equals(workspace.getOwnerId(), operatorId)
                || isAdminMember(workspace.getId(), operatorId)
                || systemAdminService.isSystemAdmin(operatorId);
    }

    private boolean canManageDeleted(WorkspaceDO workspace, long operatorId) {
        // F4/F5: a platform admin may not be a member of the workspace at all.
        return canManage(workspace, operatorId);
    }

    private boolean isAdminMember(long workspaceId, long operatorId) {
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, operatorId);
        return isActiveMember(member) && isExactLevel(member, WorkspaceAccessLevel.ADMIN);
    }

    private boolean isDeleted(WorkspaceDO workspace) {
        return Integer.valueOf(1).equals(workspace.getIsDeleted());
    }

    private void applyManageFlags(WorkspaceVO value, WorkspaceDO workspace, long operatorId) {
        boolean owner = Objects.equals(workspace.getOwnerId(), operatorId);
        value.setIsOwner(owner);
        // A platform admin manages every workspace, so the UI flags agree with canManage().
        value.setCanManage(owner
                || isAdminMember(workspace.getId(), operatorId)
                || systemAdminService.isSystemAdmin(operatorId));
    }

    private Set<String> takenActiveNames(List<WorkspaceDO> rows) {
        List<String> names = rows.stream()
                .map(WorkspaceDO::getName)
                .filter(Objects::nonNull)
                .toList();
        // listActiveNames builds an IN (...) list, and an empty one is a SQL syntax error.
        if (names.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(workspaceDao.listActiveNames(names));
    }

    /** One bulk lookup for both the owner and the deleter, deduplicated: usually the same person. */
    private Map<Long, String> userNames(List<WorkspaceDO> rows) {
        Set<Long> userIds = new HashSet<>();
        for (WorkspaceDO row : rows) {
            if (row.getOwnerId() != null) {
                userIds.add(row.getOwnerId());
            }
            if (row.getDeletedBy() != null) {
                userIds.add(row.getDeletedBy());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (UserDO user : userDao.listByIds(userIds)) {
            names.put(user.getId(), displayName(user));
        }
        return names;
    }

    private String displayName(UserDO user) {
        String nickname = user.getNickname();
        return nickname == null || nickname.isBlank() ? user.getUsername() : nickname;
    }

    private String requireName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new BizException(ErrorCode.WORKSPACE_NAME_REQUIRED);
        }
        String trimmed = rawName.trim();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "工作空间名称不能超过 " + NAME_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.length() > DESCRIPTION_MAX_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "工作空间描述不能超过 " + DESCRIPTION_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private String normalizeBackground(String background) {
        return background == null || background.isBlank() ? null : background.trim();
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
