package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.event.UserRegistrationReviewRequestedEvent;
import com.aliyun.autowonder.user.event.WorkspaceCreationAccessRequestedEvent;
import com.aliyun.autowonder.user.event.WorkspaceCreationPermissionGrantedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 平台级站内通知：注册待审、空间创建权限申请与授权结果。
 *
 * <p>全部使用平台范围（scope=PLATFORM、tenantId=0）且只写站内渠道，
 * 不伪造业务工作空间、不发送外部 IM；接收者按各自 ui.locale 渲染。
 */
@Component
public class InAppPlatformAccountListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(InAppPlatformAccountListener.class);

    static final String TYPE_REGISTRATION_REVIEW = "USER_REGISTRATION_REVIEW";
    static final String TYPE_WORKSPACE_CREATION_REQUEST = "WORKSPACE_CREATION_REQUEST";
    static final String TYPE_WORKSPACE_CREATION_GRANTED = "WORKSPACE_CREATION_GRANTED";

    /** 用户管理待审预筛选：清空关键词/页码，只按待审核状态加载。 */
    static final String REGISTRATION_REVIEW_LINK =
            "/workspaces/branding?tab=user-management&status=PENDING_REVIEW";
    /** 直达申请人详情，由前端管理员鉴权后按 ID 加载。 */
    static String workspaceCreationRequestLink(long userId) {
        return "/workspaces/branding?tab=user-management&userId=" + userId;
    }
    static final String GRANTED_LINK = "/workspaces?view=spaces";

    private final NotifyService notifyService;
    private final UserDao userDao;

    public InAppPlatformAccountListener(NotifyService notifyService, UserDao userDao) {
        this.notifyService = notifyService;
        this.userDao = userDao;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onRegistrationReviewRequested(UserRegistrationReviewRequestedEvent event) {
        try {
            List<Long> admins = availableAdminIds();
            if (admins.isEmpty()) {
                // 无可用平台管理员时不静默绕过审核：待审账号留在队列，注册响应回退通用文案。
                LOGGER.info("registration review notification skipped: no available platform admin userId={}",
                        event.userId());
                return;
            }
            for (Long adminId : admins) {
                NotifyEvent notify = new NotifyEvent();
                applyPlatformScope(notify);
                notify.setType(TYPE_REGISTRATION_REVIEW);
                notify.setTitle("有新用户注册，请审核");
                notify.setTitleKey("notification.registration_review_requested.title");
                notify.setContent("用户 " + safe(event.nickname()) + " 提交了注册申请，请审核");
                notify.setContentKey("notification.registration_review_requested.content");
                notify.setContentArgs(new Object[]{safe(event.nickname()), safe(event.username())});
                notify.setLink(REGISTRATION_REVIEW_LINK);
                notify.setRefType(TYPE_REGISTRATION_REVIEW);
                notify.setRefId(event.userId());
                notify.setRecipientIds(List.of(adminId));
                deliverIsolated(notify, "registration review", event.userId(), adminId);
            }
        } catch (Exception e) {
            LOGGER.error("failed to send registration review notifications userId={}", event.userId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onWorkspaceCreationRequested(WorkspaceCreationAccessRequestedEvent event) {
        try {
            for (Long adminId : event.recipientIds()) {
                NotifyEvent notify = new NotifyEvent();
                applyPlatformScope(notify);
                notify.setType(TYPE_WORKSPACE_CREATION_REQUEST);
                notify.setTitle("有新的空间创建权限申请");
                notify.setTitleKey("notification.workspace_creation_requested.title");
                notify.setContent(safe(event.nickname()) + "（" + safe(event.username()) + "）申请创建空间权限");
                notify.setContentKey("notification.workspace_creation_requested.content");
                notify.setContentArgs(new Object[]{safe(event.nickname()), safe(event.username())});
                notify.setLink(workspaceCreationRequestLink(event.userId()));
                notify.setRefType(TYPE_WORKSPACE_CREATION_REQUEST);
                notify.setRefId(event.requestId());
                notify.setRecipientIds(List.of(adminId));
                deliverIsolated(notify, "workspace creation request", event.requestId(), adminId);
            }
        } catch (Exception e) {
            LOGGER.error("failed to send workspace creation request notifications requestId={}",
                    event.requestId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onWorkspaceCreationGranted(WorkspaceCreationPermissionGrantedEvent event) {
        try {
            NotifyEvent notify = new NotifyEvent();
            applyPlatformScope(notify);
            notify.setType(TYPE_WORKSPACE_CREATION_GRANTED);
            notify.setTitle("空间创建权限已开通");
            notify.setTitleKey("notification.workspace_creation_granted.title");
            notify.setContent("平台管理员已为你开通空间创建权限，现在可以创建空间。");
            notify.setContentKey("notification.workspace_creation_granted.content");
            notify.setLink(GRANTED_LINK);
            notify.setRefType(TYPE_WORKSPACE_CREATION_GRANTED);
            notify.setRefId(event.userId());
            notify.setRecipientIds(List.of(event.userId()));
            deliverIsolated(notify, "workspace creation granted", event.userId(), event.userId());
        } catch (Exception e) {
            LOGGER.error("failed to send workspace creation granted notification userId={}",
                    event.userId(), e);
        }
    }

    private List<Long> availableAdminIds() {
        return userDao.listAvailablePlatformAdmins().stream()
                .filter(user -> user.getId() != null)
                .map(UserDO::getId)
                .toList();
    }

    private static void applyPlatformScope(NotifyEvent notify) {
        notify.setScope(NotificationScope.PLATFORM);
        notify.setTenantId(NotificationScope.PLATFORM_TENANT_ID);
    }

    /** 单个接收者失败只记录日志，其他管理员与待审列表不受影响。 */
    private void deliverIsolated(NotifyEvent notify, String kind, long refId, long recipientId) {
        try {
            notifyService.notify(notify);
        } catch (Exception e) {
            LOGGER.error("failed to send in-app notification kind={} refId={} recipientId={}",
                    kind, refId, recipientId, e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
