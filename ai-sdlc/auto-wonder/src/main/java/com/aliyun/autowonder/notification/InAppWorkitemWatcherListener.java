package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.dispatch.WorkitemAssignedEvent;
import com.aliyun.autowonder.im.notification.WorkitemHumanAssignedEvent;
import com.aliyun.autowonder.integration.event.WorkitemCommentCreatedEvent;
import com.aliyun.autowonder.integration.event.WorkitemStatusChangedEvent;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workitem.WorkitemCommentMentionDO;
import com.aliyun.autowonder.workitem.WorkitemCommentMentionDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemWatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 关注人（watcher）站内通知。关注只是新增的一类收件来源，不改变负责人/@/钉钉的既有通知规则。
 *
 * <p>去重口径：收件人 = 有效关注人 − 本次事件的操作者（仅真人）− 本次事件已有的专属收件人
 * （评论的 @ 对象、真人指派的新负责人）。因此同时是负责人 + 被 @ + 关注人的用户，
 * 每个事件只会收到一条有效通知。
 */
@Component
public class InAppWorkitemWatcherListener {

    private static final Logger log = LoggerFactory.getLogger(InAppWorkitemWatcherListener.class);
    private static final int MAX_CONTENT_LENGTH = 100;

    static final String TYPE_COMMENT = "WATCHED_COMMENT";
    static final String TYPE_ASSIGNED = "WATCHED_ASSIGNED";
    static final String TYPE_STATUS_CHANGED = "WATCHED_STATUS_CHANGED";
    static final String TYPE_COMPLETED = "WATCHED_COMPLETED";

    private final NotifyService notifyService;
    private final WorkitemWatcherService watcherService;
    private final WorkitemDao workitemDao;
    private final UserDao userDao;
    private final AgentDao agentDao;
    private final StatusNodeDao statusNodeDao;
    private final WorkitemCommentMentionDao commentMentionDao;

    public InAppWorkitemWatcherListener(NotifyService notifyService, WorkitemWatcherService watcherService,
            WorkitemDao workitemDao, UserDao userDao, AgentDao agentDao, StatusNodeDao statusNodeDao,
            WorkitemCommentMentionDao commentMentionDao) {
        this.notifyService = notifyService;
        this.watcherService = watcherService;
        this.workitemDao = workitemDao;
        this.userDao = userDao;
        this.agentDao = agentDao;
        this.statusNodeDao = statusNodeDao;
        this.commentMentionDao = commentMentionDao;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onCommentCreated(WorkitemCommentCreatedEvent event) {
        try {
            WorkitemDO workitem = findWorkitem(event.tenantId(), event.workitemId());
            if (workitem == null) {
                return;
            }
            Set<Long> excluded = new HashSet<>();
            if (isHuman(event.actorType())) {
                excluded.add(event.actorRef());
            }
            excluded.addAll(mentionedHumanIds(event.tenantId(), event.workitemId(), event.commentId()));
            notifyWatchers(event.tenantId(), event.workitemId(), TYPE_COMMENT, "你关注的工单有新评论",
                    actorName(event.actorType(), event.actorRef()) + " 在「" + workitem.getTitle() + "」发表了新评论："
                            + InAppCommentMentionedListener.truncate(event.contentMd(), MAX_CONTENT_LENGTH),
                    excluded);
        } catch (Exception e) {
            log.error("failed to send watcher notification for comment tenantId={} workitemId={} commentId={}",
                    event.tenantId(), event.workitemId(), event.commentId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onHumanAssigned(WorkitemHumanAssignedEvent event) {
        try {
            Set<Long> excluded = new HashSet<>();
            excluded.add(event.recipientUserId());
            if (isHuman(event.actorType())) {
                excluded.add(event.actorRef());
            }
            notifyWatchers(event.tenantId(), event.workitemId(), TYPE_ASSIGNED, "你关注的工单变更了负责人",
                    event.actorDisplayName() + " 将「" + event.workitemTitle() + "」指派给了 "
                            + humanName(event.recipientUserId()),
                    excluded);
        } catch (Exception e) {
            log.error("failed to send watcher notification for human assignment tenantId={} workitemId={}",
                    event.tenantId(), event.workitemId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onAgentAssigned(WorkitemAssignedEvent event) {
        try {
            WorkitemDO workitem = findWorkitem(event.getTenantId(), event.getWorkitemId());
            if (workitem == null) {
                return;
            }
            Set<Long> excluded = new HashSet<>();
            excluded.add(event.getUserId());
            notifyWatchers(event.getTenantId(), event.getWorkitemId(), TYPE_ASSIGNED, "你关注的工单变更了负责人",
                    "「" + workitem.getTitle() + "」已指派给数字员工 " + agentName(event.getAgentId()),
                    excluded);
        } catch (Exception e) {
            log.error("failed to send watcher notification for agent assignment tenantId={} workitemId={}",
                    event.getTenantId(), event.getWorkitemId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onStatusChanged(WorkitemStatusChangedEvent event) {
        try {
            WorkitemDO workitem = findWorkitem(event.tenantId(), event.workitemId());
            if (workitem == null) {
                return;
            }
            StatusNodeDO node = statusNodeDao.findById(event.toNodeId());
            boolean completed = isCompleted(node);
            Set<Long> excluded = new HashSet<>();
            if (event.isHumanActor()) {
                excluded.add(event.userId());
            }
            notifyWatchers(event.tenantId(), event.workitemId(),
                    completed ? TYPE_COMPLETED : TYPE_STATUS_CHANGED,
                    completed ? "你关注的工单已完成/关闭" : "你关注的工单状态有更新",
                    "「" + workitem.getTitle() + "」状态变更为 " + statusName(node),
                    excluded);
        } catch (Exception e) {
            log.error("failed to send watcher notification for status change tenantId={} workitemId={} toNodeId={}",
                    event.tenantId(), event.workitemId(), event.toNodeId(), e);
        }
    }

    private void notifyWatchers(long tenantId, long workitemId, String type, String title, String content,
            Set<Long> excludedRecipients) {
        List<Long> recipients = new ArrayList<>();
        for (Long userId : watcherService.watcherUserIds(tenantId, workitemId)) {
            if (userId != null && !excludedRecipients.contains(userId)) {
                recipients.add(userId);
            }
        }
        if (recipients.isEmpty()) {
            return;
        }
        NotifyEvent notifyEvent = new NotifyEvent();
        notifyEvent.setTenantId(tenantId);
        notifyEvent.setType(type);
        notifyEvent.setTitle(title);
        notifyEvent.setContent(content);
        notifyEvent.setLink("/workitems/" + workitemId);
        notifyEvent.setRefType("WORKITEM");
        notifyEvent.setRefId(workitemId);
        notifyEvent.setRecipientIds(recipients);
        notifyService.notify(notifyEvent);
        log.info("in-app watcher notification sent type={} tenantId={} workitemId={} recipients={}",
                type, tenantId, workitemId, recipients.size());
    }

    /** 失去工作空间访问权的关注人由 watcherService 过滤，这里只负责按工单租户校验可见性。 */
    private WorkitemDO findWorkitem(long tenantId, long workitemId) {
        WorkitemDO workitem = workitemDao.findById(workitemId);
        if (workitem == null || workitem.getTenantId() == null || workitem.getTenantId() != tenantId) {
            return null;
        }
        return workitem;
    }

    private Set<Long> mentionedHumanIds(long tenantId, long workitemId, long commentId) {
        Set<Long> ids = new HashSet<>();
        List<WorkitemCommentMentionDO> mentions = commentMentionDao.listByWorkitem(tenantId, workitemId);
        for (WorkitemCommentMentionDO mention : mentions == null ? Collections.<WorkitemCommentMentionDO>emptyList()
                : mentions) {
            if (mention == null || mention.getCommentId() == null || mention.getCommentId() != commentId) {
                continue;
            }
            if ("HUMAN".equals(mention.getTargetType()) && mention.getTargetRef() != null) {
                ids.add(mention.getTargetRef());
            }
        }
        return ids;
    }

    private static boolean isHuman(String actorType) {
        return "HUMAN".equals(actorType);
    }

    /** 状态分类为 DONE(完成/发布)或 CANCELED(关闭/取消)视为终态，对应需求的"完成/关闭"通知。 */
    private boolean isCompleted(StatusNodeDO node) {
        if (node == null || node.getCategory() == null) {
            return false;
        }
        String category = node.getCategory();
        return "DONE".equalsIgnoreCase(category) || "CANCELED".equalsIgnoreCase(category);
    }

    private String statusName(StatusNodeDO node) {
        if (node == null) {
            return "未知状态";
        }
        if (node.getName() != null && !node.getName().isBlank()) {
            return node.getName();
        }
        return node.getCode() == null ? "未知状态" : node.getCode();
    }

    private String actorName(String actorType, long actorRef) {
        if ("AGENT".equals(actorType)) {
            return agentName(actorRef);
        }
        return humanName(actorRef);
    }

    private String humanName(long userId) {
        UserDO user = userDao.findById(userId);
        String name = WorkitemWatcherService.userName(user);
        return name == null || name.isBlank() ? String.valueOf(userId) : name;
    }

    private String agentName(Long agentId) {
        if (agentId == null) {
            return "数字员工";
        }
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null || agent.getName() == null || agent.getName().isBlank()) {
            return String.valueOf(agentId);
        }
        return agent.getName();
    }
}
