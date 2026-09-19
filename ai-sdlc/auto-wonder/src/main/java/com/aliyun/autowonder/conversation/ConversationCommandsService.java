package com.aliyun.autowonder.conversation;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.conversation.dto.ClarificationSlashCommandVO;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 斜杠命令「按需获取」编排：触发带外探针、缓存命令快照、把结果推给浏览器。
 *
 * <p>命令是会话级能力数据、不进轮次事件流；快照走 Redis（不动任何 DB 表）。
 * 探针有进程开销，用 Redis 短锁去重，避免频繁开关会话导致 spawn 风暴。
 *
 * <p>日志<b>不</b>打印命令载荷正文（可能很大）：正常路径只记计数/键，异常只记 warn。
 */
@Service
public class ConversationCommandsService {

    private static final Logger log = LoggerFactory.getLogger(ConversationCommandsService.class);
    static final String SNAPSHOT_KEY_PREFIX = "autowonder:clarification:commands:v1:";
    static final String PROBE_LOCK_PREFIX = "autowonder:clarification:commands:lock:v1:";
    /** 快照 TTL：命令准静态，1 小时足够；打开会话仍会后台 revalidate。 */
    static final long SNAPSHOT_TTL_SEC = 3600L;
    /** 探针去重锁 TTL：略大于执行器探针超时（25s），防止并发重复拉起。 */
    static final long PROBE_LOCK_TTL_SEC = 30L;

    private final AgentConversationDao convDao;
    private final ConversationTransport transport;
    private final RedisManager redis;
    /** 用 setter 注入：与 ConversationElicitationService 一致，publisher 是可选依赖。 */
    private ConversationBrowserEventPublisher browserEventPublisher;

    public ConversationCommandsService(AgentConversationDao convDao, ConversationTransport transport,
            RedisManager redis) {
        this.convDao = convDao;
        this.transport = transport;
        this.redis = redis;
    }

    @Autowired(required = false)
    public void setBrowserEventPublisher(ConversationBrowserEventPublisher publisher) {
        this.browserEventPublisher = publisher;
    }

    /** 前端打开会话时调用：抢到去重锁才发探针，否则说明已有探针在途。 */
    public void refresh(long tenantId, long conversationId) {
        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null || conv.getExecutorId() == null) {
            log.info("commands refresh skipped: no bound executor conversationId={}", conversationId);
            return;
        }
        boolean acquired;
        try {
            acquired = redis.setIfAbsent(PROBE_LOCK_PREFIX + conversationId, "1",
                    PROBE_LOCK_TTL_SEC);
        } catch (RuntimeException e) {
            // Redis 故障时无法判断是否有探针在途，宁可放弃本次探针也不能
            // 让异常抛穿接口：探针是非关键辅助能力，前端本就是 fire-and-forget。
            log.warn("commands refresh lock degraded conversationId={}: {}", conversationId,
                    e.getMessage());
            return;
        }
        if (!acquired) {
            return;
        }
        try {
            transport.sendCommandsProbe(conv);
        } catch (RuntimeException e) {
            log.warn("commands probe dispatch failed conversationId={}: {}", conversationId,
                    e.getMessage());
        }
    }

    /**
     * 执行器回传命令结果：OK 才写快照并推浏览器；失败静默（前端下次打开重试）。
     *
     * <p>{@code conversationId} 来自执行器帧，属于不可信输入，必须先校验归属再落键/推送
     * （同 {@code ConversationTurnEventService.persistEvent}）：否则任一在线执行器可伪报
     * 他人会话 id，把任意命令写进别人的快照与浏览器事件通道。
     */
    public void onResult(long tenantId, long executorId, long conversationId, String status,
            String commandsJson, String error) {
        if (conversationId <= 0) {
            log.warn("commands result rejected: malformed conversationId={} executorId={}",
                    conversationId, executorId);
            return;
        }
        AgentConversationDO conv = convDao.findById(tenantId, conversationId);
        if (conv == null || conv.getExecutorId() == null || conv.getExecutorId() != executorId) {
            log.warn("commands result rejected: executor mismatch tenantId={} conversationId={} "
                    + "executorId={} expectedExecutorId={}",
                    tenantId, conversationId, executorId,
                    conv != null ? conv.getExecutorId() : null);
            return;
        }
        if (!"OK".equals(status) || commandsJson == null || commandsJson.isBlank()) {
            log.info("commands probe not ok conversationId={} status={} error={}", conversationId,
                    status, error);
            return;
        }
        // 快照缓存与浏览器推送是两个独立的辅助动作，各自降级：
        // Redis 写失败不该连累浏览器实时收到命令，反之亦然。
        try {
            redis.setWithExpire(SNAPSHOT_KEY_PREFIX + conversationId, commandsJson, SNAPSHOT_TTL_SEC);
        } catch (RuntimeException e) {
            log.warn("commands snapshot write degraded conversationId={}: {}", conversationId,
                    e.getMessage());
        }
        if (browserEventPublisher != null) {
            try {
                String payload = "{\"type\":\"acp_commands\",\"data\":" + commandsJson + "}";
                browserEventPublisher.publishServerEvent(tenantId, conversationId, 0L, "acp_commands",
                        payload);
            } catch (RuntimeException e) {
                log.warn("commands browser event degraded conversationId={}: {}", conversationId,
                        e.getMessage());
            }
        }
    }

    /**
     * 会话详情种子：返回上次快照（可能为空），前端打开即秒显。
     *
     * <p>Redis 读失败只损失秒显种子，必须降级为空列表而不是把异常抛给会话详情：
     * 命令快照是非关键辅助信息，不能让它阻断已有历史的返回（工单 55411 修复要求 3）。
     */
    public List<ClarificationSlashCommandVO> snapshot(long tenantId, long conversationId) {
        String json;
        try {
            json = redis.getString(SNAPSHOT_KEY_PREFIX + conversationId);
        } catch (RuntimeException e) {
            log.warn("commands snapshot read degraded conversationId={}: {}", conversationId,
                    e.getMessage());
            return List.of();
        }
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JSONObject root = JSON.parseObject(json);
            List<ClarificationSlashCommandVO> commands = JSON.parseArray(
                    root.getString("availableCommands"), ClarificationSlashCommandVO.class);
            return commands == null ? List.of() : commands;
        } catch (RuntimeException e) {
            log.warn("commands snapshot unparsable conversationId={}: {}", conversationId,
                    e.getMessage());
            return List.of();
        }
    }
}
