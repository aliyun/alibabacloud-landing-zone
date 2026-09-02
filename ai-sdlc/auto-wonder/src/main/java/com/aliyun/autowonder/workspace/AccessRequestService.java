package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workspace.dto.AccessRequestVO;
import com.aliyun.autowonder.workspace.dto.WorkspaceListItemVO;
import com.aliyun.autowonder.workspace.event.WorkspaceAccessCancelledEvent;
import com.aliyun.autowonder.workspace.event.WorkspaceAccessRequestedEvent;
import com.aliyun.autowonder.workspace.event.WorkspaceAccessReviewedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AccessRequestService {

    private static final Logger log = LoggerFactory.getLogger(AccessRequestService.class);

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final Set<String> REVIEWABLE_STATUSES =
            Set.of(STATUS_PENDING, STATUS_APPROVED, STATUS_REJECTED);

    private static final String MEMBERSHIP_MEMBER = "MEMBER";
    private static final String MEMBERSHIP_PENDING = "PENDING";
    private static final String MEMBERSHIP_NOT_MEMBER = "NOT_MEMBER";

    private static final int MEMBER_STATUS_ACTIVE = 0;

    private final WorkspaceDao workspaceDao;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final AccessRequestDao accessRequestDao;
    private final UserDao userDao;
    private final ApplicationEventPublisher eventPublisher;

    public AccessRequestService(WorkspaceDao workspaceDao,
                                WorkspaceMemberDao workspaceMemberDao,
                                AccessRequestDao accessRequestDao,
                                UserDao userDao,
                                ApplicationEventPublisher eventPublisher) {
        this.workspaceDao = workspaceDao;
        this.workspaceMemberDao = workspaceMemberDao;
        this.accessRequestDao = accessRequestDao;
        this.userDao = userDao;
        this.eventPublisher = eventPublisher;
    }

    public PageResult<WorkspaceListItemVO> listAll(String keyword, int page, int size, long currentUserId) {
        int offset = (page - 1) * size;
        List<WorkspaceDO> workspaces = workspaceDao.listAllPaged(keyword, offset, size);
        long total = workspaceDao.countAll(keyword);

        // Two bulk lookups instead of per-row queries: the caller's memberships and pending requests.
        Map<Long, String> levelByWorkspaceId = new HashMap<>();
        for (WorkspaceMembershipDO membership : workspaceDao.listMembershipsByUser(currentUserId)) {
            levelByWorkspaceId.put(membership.getId(), membership.getAccessLevel());
        }
        Map<Long, Long> pendingRequestIdByWorkspaceId = new HashMap<>();
        for (AccessRequestDO request : accessRequestDao.listPendingByRequester(currentUserId)) {
            pendingRequestIdByWorkspaceId.put(request.getTenantId(), request.getId());
        }

        List<WorkspaceListItemVO> items = new ArrayList<>(workspaces.size());
        for (WorkspaceDO workspace : workspaces) {
            WorkspaceListItemVO item = new WorkspaceListItemVO();
            item.setId(workspace.getId());
            item.setName(workspace.getName());
            item.setDescription(workspace.getDescription());

            String memberLevel = levelByWorkspaceId.get(workspace.getId());
            if (memberLevel != null) {
                item.setMembershipStatus(MEMBERSHIP_MEMBER);
                item.setAccessLevel(memberLevel);
            } else if (pendingRequestIdByWorkspaceId.containsKey(workspace.getId())) {
                item.setMembershipStatus(MEMBERSHIP_PENDING);
                item.setPendingRequestId(pendingRequestIdByWorkspaceId.get(workspace.getId()));
            } else {
                item.setMembershipStatus(MEMBERSHIP_NOT_MEMBER);
            }
            items.add(item);
        }
        return new PageResult<>(items, total, page, size);
    }

    @Transactional
    public void submitRequest(long workspaceId, String requestedLevel, long requesterId) {
        WorkspaceAccessLevel level = parseLevel(requestedLevel);

        WorkspaceDO workspace = workspaceDao.findById(workspaceId);
        if (workspace == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }

        // findByWorkspaceAndUser does not filter status, so a deactivated member row must not block
        // a fresh request; only status = 0 (ACTIVE) counts as already being a member.
        if (isActiveMember(workspaceMemberDao.findByWorkspaceAndUser(workspaceId, requesterId))) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_REQUEST_ALREADY_MEMBER);
        }

        if (accessRequestDao.findPendingByTenantAndRequester(workspaceId, requesterId) != null) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_REQUEST_DUPLICATE);
        }

        AccessRequestDO request = new AccessRequestDO();
        request.setTenantId(workspaceId);
        request.setRequesterId(requesterId);
        request.setRequestedLevel(level.name());
        request.setStatus(STATUS_PENDING);
        try {
            accessRequestDao.insert(request);
        } catch (DuplicateKeyException race) {
            // The pre-check is only a friendly fast path; two concurrent submissions can both pass it
            // and the (tenant_id, requester_id, pending_marker) unique index rejects the loser.
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_REQUEST_DUPLICATE);
        }

        eventPublisher.publishEvent(new WorkspaceAccessRequestedEvent(
                workspaceId, request.getId(), requesterId,
                displayName(requesterId), level.name(), workspace.getName()));
    }

    public List<AccessRequestVO> listForWorkspace(long workspaceId, String status) {
        if (status == null || status.isBlank() || !REVIEWABLE_STATUSES.contains(status)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "Invalid access request status");
        }

        List<AccessRequestDO> requests = accessRequestDao.listByTenantAndStatus(workspaceId, status);
        if (requests.isEmpty()) {
            return List.of();
        }

        Set<Long> userIds = new LinkedHashSet<>();
        for (AccessRequestDO request : requests) {
            if (request.getRequesterId() != null) {
                userIds.add(request.getRequesterId());
            }
            if (request.getReviewerId() != null) {
                userIds.add(request.getReviewerId());
            }
        }
        Map<Long, String> nameByUserId = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (UserDO user : userDao.listByIds(userIds)) {
                nameByUserId.put(user.getId(), user.getNickname());
            }
        }

        List<AccessRequestVO> items = new ArrayList<>(requests.size());
        for (AccessRequestDO request : requests) {
            AccessRequestVO vo = new AccessRequestVO();
            vo.setId(request.getId());
            vo.setTenantId(request.getTenantId());
            vo.setRequesterId(request.getRequesterId());
            vo.setRequesterName(nameByUserId.get(request.getRequesterId()));
            vo.setRequestedLevel(request.getRequestedLevel());
            vo.setStatus(request.getStatus());
            vo.setReviewerId(request.getReviewerId());
            vo.setReviewerName(nameByUserId.get(request.getReviewerId()));
            vo.setRejectReason(request.getRejectReason());
            vo.setGmtCreate(request.getGmtCreate());
            items.add(vo);
        }
        return items;
    }

    @Transactional
    public void approve(long workspaceId, long requestId, long reviewerId) {
        AccessRequestDO request = requirePendingRequest(workspaceId, requestId);

        if (accessRequestDao.updateStatus(requestId, STATUS_APPROVED, reviewerId, null) == 0) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_REQUEST_NOT_FOUND);
        }

        // insertOrActivate is an unconditional INSERT ... ON DUPLICATE KEY UPDATE, so writing through it
        // for someone who is already an active member would overwrite their access_level with the
        // (possibly lower) requested one and erase their identity_tags. A request can outlive the
        // requester's non-membership: WorkspaceService.addMember grants membership without cancelling
        // outstanding PENDING requests. Resolving such a stale entry must not touch the membership.
        WorkspaceMemberDO existing =
                workspaceMemberDao.findByWorkspaceAndUser(workspaceId, request.getRequesterId());
        if (!isActiveMember(existing)) {
            WorkspaceMemberDO member = new WorkspaceMemberDO();
            member.setTenantId(workspaceId);
            member.setUserId(request.getRequesterId());
            member.setStatus(MEMBER_STATUS_ACTIVE);
            member.setAccessLevel(request.getRequestedLevel());
            member.setIdentityTags(IdentityTags.toJson(List.of()));
            member.setCreatorId(reviewerId);
            member.setModifierId(reviewerId);
            workspaceMemberDao.insertOrActivate(member);
        }

        publishReviewed(workspaceId, request, reviewerId, STATUS_APPROVED, null);
    }

    @Transactional
    public void reject(long workspaceId, long requestId, long reviewerId, String reason) {
        AccessRequestDO request = requirePendingRequest(workspaceId, requestId);

        if (accessRequestDao.updateStatus(requestId, STATUS_REJECTED, reviewerId, reason) == 0) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_REQUEST_NOT_FOUND);
        }

        publishReviewed(workspaceId, request, reviewerId, STATUS_REJECTED, reason);
    }

    @Transactional
    public void cancelRequest(long workspaceId, long requestId, long operatorId) {
        AccessRequestDO request = accessRequestDao.findById(requestId);
        if (request == null
                || request.getTenantId() == null || request.getTenantId() != workspaceId) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_REQUEST_NOT_FOUND);
        }
        if (!STATUS_PENDING.equals(request.getStatus())) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_REQUEST_NOT_PENDING);
        }
        if (request.getRequesterId() == null || request.getRequesterId() != operatorId) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_REQUEST_NOT_REQUESTER);
        }

        // Physical delete is the product requirement; the PENDING guard means a request that a
        // reviewer resolved between findById and here survives, and the lost race surfaces as
        // the same friendly NOT_FOUND instead of a system error.
        if (accessRequestDao.deletePendingById(requestId) == 0) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_REQUEST_NOT_FOUND);
        }

        // FR-7: system log only (operator/time/request id), deliberately not the audit log.
        log.info("workspace access request cancelled tenantId={} requestId={} operatorId={}",
                workspaceId, requestId, operatorId);

        WorkspaceDO workspace = workspaceDao.findById(workspaceId);
        eventPublisher.publishEvent(new WorkspaceAccessCancelledEvent(
                workspaceId, requestId, operatorId,
                displayName(operatorId), request.getRequestedLevel(),
                workspace == null ? null : workspace.getName()));
    }

    // Mirrors WorkspaceService.addMember's guard: only status = 0 counts as an active membership.
    // The is_deleted = 0 filter is already applied by findByWorkspaceAndUser.
    private boolean isActiveMember(WorkspaceMemberDO member) {
        return member != null && Integer.valueOf(MEMBER_STATUS_ACTIVE).equals(member.getStatus());
    }

    private AccessRequestDO requirePendingRequest(long workspaceId, long requestId) {
        AccessRequestDO request = accessRequestDao.findById(requestId);
        if (request == null
                || request.getTenantId() == null || request.getTenantId() != workspaceId
                || !STATUS_PENDING.equals(request.getStatus())) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_REQUEST_NOT_FOUND);
        }
        return request;
    }

    private void publishReviewed(long workspaceId, AccessRequestDO request, long reviewerId,
                                 String outcome, String rejectReason) {
        WorkspaceDO workspace = workspaceDao.findById(workspaceId);
        eventPublisher.publishEvent(new WorkspaceAccessReviewedEvent(
                workspaceId, request.getId(), request.getRequesterId(),
                reviewerId, displayName(reviewerId),
                workspace == null ? null : workspace.getName(),
                request.getRequestedLevel(), outcome, rejectReason));
    }

    private String displayName(long userId) {
        UserDO user = userDao.findById(userId);
        if (user == null || user.getNickname() == null || user.getNickname().isBlank()) {
            return String.valueOf(userId);
        }
        return user.getNickname();
    }

    private WorkspaceAccessLevel parseLevel(String requestedLevel) {
        // WorkspaceAccessLevel.valueOf(null) throws NPE rather than IllegalArgumentException,
        // so null and blank are rejected before the enum lookup.
        if (requestedLevel == null || requestedLevel.isBlank()) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_REQUEST_LEVEL_INVALID);
        }
        try {
            return WorkspaceAccessLevel.valueOf(requestedLevel);
        } catch (IllegalArgumentException unknown) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_REQUEST_LEVEL_INVALID);
        }
    }
}
