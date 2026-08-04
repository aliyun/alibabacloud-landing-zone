package com.aliyun.autowonder.org;

import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.org.dto.CreateOrgRequest;
import com.aliyun.autowonder.org.dto.CurrentMembershipVO;
import com.aliyun.autowonder.org.dto.MemberCandidateVO;
import com.aliyun.autowonder.org.dto.MemberVO;
import com.aliyun.autowonder.org.dto.OrgVO;
import com.aliyun.autowonder.org.dto.SwitchOrgResponse;
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
public class OrgService {
    private static final int MEMBER_CANDIDATE_LIMIT = 20;
    private static final String AUDIT_ACTOR_HUMAN = "HUMAN";
    private static final String AUDIT_MODULE_ORG = "ORG";

    private final OrgDao orgDao;
    private final OrgMemberDao orgMemberDao;
    private final StatusTemplateSeeder statusTemplateSeeder;
    private final JwtService jwtService;
    private final UserDao userDao;
    private final AuditLogService auditLogService;

    public OrgService(OrgDao orgDao, OrgMemberDao orgMemberDao,
                      StatusTemplateSeeder statusTemplateSeeder, JwtService jwtService,
                      UserDao userDao, AuditLogService auditLogService) {
        this.orgDao = orgDao;
        this.orgMemberDao = orgMemberDao;
        this.statusTemplateSeeder = statusTemplateSeeder;
        this.jwtService = jwtService;
        this.userDao = userDao;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public OrgVO create(CreateOrgRequest req, long ownerUserId) {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.ORG_NAME_REQUIRED);
        }
        String trimmedName = req.getName().trim();
        if (orgDao.findByName(trimmedName) != null) {
            throw new BizException(ErrorCode.ORG_NAME_DUPLICATE);
        }

        OrgDO org = new OrgDO();
        org.setName(trimmedName);
        org.setDescription(req.getDescription());
        org.setBackground(req.getBackground());
        org.setOwnerId(ownerUserId);
        org.setStatus(0);
        org.setCreatorId(ownerUserId);
        orgDao.insert(org);

        OrgMemberDO owner = new OrgMemberDO();
        owner.setTenantId(org.getId());
        owner.setUserId(ownerUserId);
        owner.setStatus(0);
        owner.setAccessLevel(OrgAccessLevel.ADMIN.name());
        owner.setIdentityTags(IdentityTags.toJson(List.of()));
        owner.setCreatorId(ownerUserId);
        orgMemberDao.insert(owner);

        statusTemplateSeeder.seed(org.getId(), ownerUserId);

        OrgVO result = new OrgVO();
        result.setId(org.getId());
        result.setName(org.getName());
        result.setDescription(org.getDescription());
        return result;
    }

    public List<OrgVO> listByUser(long userId) {
        List<OrgVO> result = new ArrayList<>();
        for (OrgDO org : orgDao.listByUser(userId)) {
            result.add(toVO(org));
        }
        return result;
    }

    public List<OrgVO> listByUserWithAccess(long userId) {
        List<OrgVO> result = new ArrayList<>();
        for (OrgMembershipDO membership : orgDao.listMembershipsByUser(userId)) {
            OrgVO value = new OrgVO();
            value.setId(membership.getId());
            value.setName(membership.getName());
            value.setDescription(membership.getDescription());
            value.setAccessLevel(exactAccessLevel(membership.getAccessLevel()));
            result.add(value);
        }
        return result;
    }

    public OrgAccessLevel activeAccessLevel(long orgId, long userId) {
        OrgMemberDO member = requireActiveMember(
                orgMemberDao.findByOrgAndUser(orgId, userId));
        return exactAccessLevel(member.getAccessLevel());
    }

    public OrgVO scopedOrg(long orgId, OrgAccessLevel accessLevel) {
        OrgVO result = getCurrent(orgId);
        result.setAccessLevel(accessLevel);
        return result;
    }

    public OrgVO getCurrent(long orgId) {
        OrgDO org = orgDao.findById(orgId);
        if (org == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "组织不存在");
        }
        return toVO(org);
    }

    private OrgVO toVO(OrgDO org) {
        OrgVO value = new OrgVO();
        value.setId(org.getId());
        value.setName(org.getName());
        value.setDescription(org.getDescription());
        return value;
    }

    public SwitchOrgResponse switchOrg(long orgId, long userId) {
        OrgMemberDO member = requireActiveMember(orgMemberDao.findByOrgAndUser(orgId, userId));
        OrgAccessLevel accessLevel = exactAccessLevel(member.getAccessLevel());

        AutoWonderContext context = AutoWonderContext.get();
        context.setCurrentOrgId(orgId);
        context.setOrgAccessLevel(accessLevel);

        String accessToken = jwtService.signAccess(
                new TokenPayload(userId, orgId, UUID.randomUUID().toString()));
        return new SwitchOrgResponse(accessToken, accessLevel);
    }

    public List<MemberVO> listMembers(long orgId) {
        OrgDO org = orgDao.findById(orgId);
        List<MemberVO> result = new ArrayList<>();
        for (OrgMemberDO member : orgMemberDao.listByTenant(orgId)) {
            MemberVO value = new MemberVO();
            value.setUserId(member.getUserId());
            value.setJoinedAt(member.getJoinedAt());
            value.setOwner(org != null && Objects.equals(org.getOwnerId(), member.getUserId()));
            value.setAccessLevel(exactAccessLevel(member.getAccessLevel()));
            value.setIdentityTags(IdentityTags.fromJson(member.getIdentityTags()));
            applyUserIdentity(value, userDao.findById(member.getUserId()));
            result.add(value);
        }
        return result;
    }

    public CurrentMembershipVO currentMembership(long orgId, long userId) {
        OrgMemberDO member = currentRequestMember(orgId, userId);
        OrgDO org = orgDao.findById(orgId);
        CurrentMembershipVO result = new CurrentMembershipVO();
        result.setUserId(member.getUserId());
        result.setJoinedAt(member.getJoinedAt());
        result.setOwner(org != null && Objects.equals(org.getOwnerId(), member.getUserId()));
        result.setAccessLevel(exactAccessLevel(member.getAccessLevel()));
        result.setIdentityTags(IdentityTags.fromJson(member.getIdentityTags()));
        applyUserIdentity(result, userDao.findById(member.getUserId()));
        return result;
    }

    private OrgMemberDO currentRequestMember(long orgId, long userId) {
        AutoWonderContext context = AutoWonderContext.get();
        OrgMemberDO member = context.getOrgMember();
        if (Objects.equals(context.getCurrentOrgId(), orgId)
                && Objects.equals(context.getUserId(), userId)
                && member != null
                && Objects.equals(member.getTenantId(), orgId)
                && Objects.equals(member.getUserId(), userId)) {
            return requireActiveMember(member);
        }
        return requireActiveMember(orgMemberDao.findByOrgAndUser(orgId, userId));
    }

    public List<MemberCandidateVO> searchMemberCandidates(long orgId, String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        List<UserDO> users = userDao.searchOrgCandidates(
                orgId, normalizedKeyword, MEMBER_CANDIDATE_LIMIT);
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
    public void addMember(long orgId, Long targetUserId, long operatorId) {
        if (targetUserId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户不能为空");
        }
        UserDO targetUser = userDao.findById(targetUserId);
        if (targetUser == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        OrgMemberDO existing = orgMemberDao.findByOrgAndUser(orgId, targetUserId);
        if (isActiveMember(existing)) {
            return;
        }
        if (!Integer.valueOf(0).equals(targetUser.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户不可添加");
        }

        OrgMemberDO member = new OrgMemberDO();
        member.setTenantId(orgId);
        member.setUserId(targetUserId);
        member.setStatus(0);
        member.setAccessLevel(OrgAccessLevel.READ_ONLY.name());
        member.setIdentityTags(IdentityTags.toJson(List.of()));
        member.setCreatorId(operatorId);
        member.setModifierId(operatorId);
        orgMemberDao.insertOrActivate(member);
    }

    @Transactional
    public void updateMemberAccess(long orgId, long targetUserId,
                                   OrgAccessLevel requestedLevel, long operatorId) {
        if (requestedLevel == null) {
            throw new BizException(ErrorCode.ORG_ACCESS_LEVEL_INVALID);
        }
        if (targetUserId == operatorId) {
            throw new BizException(ErrorCode.ORG_SELF_LEVEL_MUTATION_FORBIDDEN);
        }
        OrgDO org = orgDao.findByIdForUpdate(orgId);
        if (org != null && Objects.equals(org.getOwnerId(), targetUserId)) {
            throw new BizException(ErrorCode.ORG_OWNER_MUTATION_PROTECTED);
        }

        OrgMemberDO target = requireActiveMember(
                orgMemberDao.findByOrgAndUserForUpdate(orgId, targetUserId));
        OrgAccessLevel oldLevel = exactAccessLevel(target.getAccessLevel());
        int updated = orgMemberDao.updateAccessLevel(
                orgId, targetUserId, requestedLevel.name(), operatorId);
        requireSingleUpdate(updated);

        AuditLogRecord audit = memberAudit(
                orgId, operatorId, targetUserId, "MEMBER_ACCESS_CHANGED");
        audit.detail("oldAccessLevel", oldLevel.name())
                .detail("newAccessLevel", requestedLevel.name())
                .detail("operatorId", operatorId)
                .detail("targetUserId", targetUserId);
        auditLogService.recordRequired(audit);
    }

    @Transactional
    public void updateMemberIdentityTags(long orgId, long targetUserId,
                                         List<String> requestedTags, long operatorId) {
        OrgMemberDO target = requireActiveMember(
                orgMemberDao.findByOrgAndUserForUpdate(orgId, targetUserId));
        List<String> oldTags = IdentityTags.fromJson(target.getIdentityTags());
        List<String> newTags = IdentityTags.normalize(requestedTags);
        int updated = orgMemberDao.updateIdentityTags(
                orgId, targetUserId, IdentityTags.toJson(newTags), operatorId);
        requireSingleUpdate(updated);

        AuditLogRecord audit = memberAudit(
                orgId, operatorId, targetUserId, "MEMBER_IDENTITY_TAGS_CHANGED");
        audit.detail("oldIdentityTags", oldTags)
                .detail("newIdentityTags", newTags)
                .detail("operatorId", operatorId)
                .detail("targetUserId", targetUserId);
        auditLogService.recordRequired(audit);
    }

    @Transactional
    public void removeMember(long orgId, long targetUserId, long operatorId) {
        OrgDO org = orgDao.findByIdForUpdate(orgId);
        if (org != null && Objects.equals(org.getOwnerId(), targetUserId)) {
            throw new BizException(ErrorCode.ORG_OWNER_MUTATION_PROTECTED);
        }
        requireActiveMember(orgMemberDao.findByOrgAndUserForUpdate(orgId, targetUserId));
        requireSingleUpdate(orgMemberDao.softDelete(orgId, targetUserId, operatorId));
    }

    @Transactional
    public void transferOwner(long orgId, long targetUserId, long operatorId) {
        OrgDO org = orgDao.findByIdForUpdate(orgId);
        if (org == null
                || !Objects.equals(org.getOwnerId(), operatorId)
                || targetUserId == operatorId) {
            throw ownerTransferInvalid();
        }

        OrgMemberDO currentOwner = orgMemberDao.findByOrgAndUserForUpdate(orgId, operatorId);
        OrgMemberDO target = orgMemberDao.findByOrgAndUserForUpdate(orgId, targetUserId);
        if (!isActiveMember(currentOwner)
                || !isExactLevel(currentOwner, OrgAccessLevel.ADMIN)
                || !isActiveMember(target)) {
            throw ownerTransferInvalid();
        }

        OrgAccessLevel targetLevel;
        try {
            targetLevel = exactAccessLevel(target.getAccessLevel());
        } catch (BizException exception) {
            throw ownerTransferInvalid();
        }
        if (targetLevel != OrgAccessLevel.ADMIN) {
            int promoted = orgMemberDao.updateAccessLevel(
                    orgId, targetUserId, OrgAccessLevel.ADMIN.name(), operatorId);
            if (promoted != 1) {
                throw ownerTransferInvalid();
            }
        }

        int ownerUpdated = orgDao.updateOwner(
                orgId, operatorId, targetUserId, operatorId);
        if (ownerUpdated != 1) {
            throw ownerTransferInvalid();
        }

        AuditLogRecord audit = audit(
                orgId, operatorId, "ORG_OWNER_TRANSFERRED", "ORG", orgId);
        audit.detail("oldOwnerId", operatorId)
                .detail("newOwnerId", targetUserId)
                .detail("operatorId", operatorId)
                .detail("targetUserId", targetUserId);
        auditLogService.recordRequired(audit);
    }

    private OrgMemberDO requireActiveMember(OrgMemberDO member) {
        if (!isActiveMember(member)) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return member;
    }

    private boolean isActiveMember(OrgMemberDO member) {
        return member != null
                && Integer.valueOf(0).equals(member.getStatus())
                && Integer.valueOf(0).equals(member.getIsDeleted());
    }

    private OrgAccessLevel exactAccessLevel(String persistedLevel) {
        try {
            return OrgAccessLevel.valueOf(persistedLevel);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BizException(ErrorCode.ORG_ACCESS_LEVEL_INVALID);
        }
    }

    private boolean isExactLevel(OrgMemberDO member, OrgAccessLevel expected) {
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
        return new BizException(ErrorCode.ORG_OWNER_TRANSFER_INVALID);
    }

    private AuditLogRecord memberAudit(long orgId, long operatorId,
                                       long targetUserId, String event) {
        return audit(orgId, operatorId, event, "MEMBER", targetUserId);
    }

    private AuditLogRecord audit(long orgId, long operatorId, String event,
                                 String targetType, long targetId) {
        AuditLogRecord audit = new AuditLogRecord();
        audit.setTenantId(orgId);
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
