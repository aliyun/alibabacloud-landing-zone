package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationCommandsServiceTest {

    private AgentConversationDao convDao;
    private ConversationTransport transport;
    private RedisManager redis;
    private ConversationBrowserEventPublisher publisher;
    private ConversationCommandsService service;

    @BeforeEach
    void setUp() {
        convDao = mock(AgentConversationDao.class);
        transport = mock(ConversationTransport.class);
        redis = mock(RedisManager.class);
        publisher = mock(ConversationBrowserEventPublisher.class);
        service = new ConversationCommandsService(convDao, transport, redis);
        service.setBrowserEventPublisher(publisher);
    }

    private AgentConversationDO conv() {
        AgentConversationDO c = new AgentConversationDO();
        c.setId(42L);
        c.setTenantId(1L);
        c.setExecutorId(7L);
        return c;
    }

    /** 抢到去重锁才下发探针。 */
    @Test
    void refreshSendsProbeWhenLockAcquired() {
        when(convDao.findById(1L, 42L)).thenReturn(conv());
        when(redis.setIfAbsent(anyString(), anyString(), anyLong())).thenReturn(true);

        service.refresh(1L, 42L);

        verify(transport).sendCommandsProbe(argThat(c -> c.getId() == 42L));
    }

    /** 已有探针在途（抢不到锁）时不重复下发，避免 spawn 风暴。 */
    @Test
    void refreshSkipsWhenProbeInFlight() {
        when(convDao.findById(1L, 42L)).thenReturn(conv());
        when(redis.setIfAbsent(anyString(), anyString(), anyLong())).thenReturn(false);

        service.refresh(1L, 42L);

        verify(transport, never()).sendCommandsProbe(any());
    }

    /** OK 结果写 Redis 快照并把命令原样推给浏览器。 */
    @Test
    void onResultOkWritesSnapshotAndPublishes() {
        String cmds = "{\"availableCommands\":[{\"name\":\"quest\"}]}";
        when(convDao.findById(1L, 42L)).thenReturn(conv());

        service.onResult(1L, 7L, 42L, "OK", cmds, "");

        verify(redis).setWithExpire(eq("autowonder:clarification:commands:v1:42"), anyString(),
                anyLong());
        verify(publisher).publishServerEvent(eq(1L), eq(42L), eq(0L), eq("acp_commands"),
                eq("{\"type\":\"acp_commands\",\"data\":" + cmds + "}"));
    }

    /** 非 OK 结果不写快照、不推浏览器（前端下次打开重试）。 */
    @Test
    void onResultFailureDoesNotWriteSnapshot() {
        when(convDao.findById(1L, 42L)).thenReturn(conv());

        service.onResult(1L, 7L, 42L, "TIMEOUT", "", "boom");

        verify(redis, never()).setWithExpire(anyString(), anyString(), anyLong());
        verify(publisher, never()).publishServerEvent(anyLong(), anyLong(), anyLong(), anyString(),
                anyString());
    }

    /**
     * 回传帧的 conversationId 由执行器提供，必须校验归属（镜像 persistEvent）：
     * 否则任一在线执行器可伪报他人会话的 conversationId，把任意命令注入别人的
     * 快照与浏览器事件通道。未绑定会话与 executorId 不匹配都要静默丢弃。
     */
    @Test
    void onResultIgnoresForeignExecutor() {
        // 会话不存在
        when(convDao.findById(1L, 42L)).thenReturn(null);
        service.onResult(1L, 7L, 42L, "OK", "{\"availableCommands\":[{\"name\":\"quest\"}]}", "");
        // 会话绑在别的执行器上
        when(convDao.findById(1L, 43L)).thenReturn(conv());
        service.onResult(1L, 8L, 43L, "OK", "{\"availableCommands\":[{\"name\":\"quest\"}]}", "");
        // 会话从未绑定执行器
        AgentConversationDO unbound = conv();
        unbound.setExecutorId(null);
        when(convDao.findById(1L, 44L)).thenReturn(unbound);
        service.onResult(1L, 7L, 44L, "OK", "{\"availableCommands\":[{\"name\":\"quest\"}]}", "");
        // conversationId 非法
        service.onResult(1L, 7L, 0L, "OK", "{\"availableCommands\":[{\"name\":\"quest\"}]}", "");

        verify(redis, never()).setWithExpire(anyString(), anyString(), anyLong());
        verify(publisher, never()).publishServerEvent(anyLong(), anyLong(), anyLong(), anyString(),
                anyString());
        verify(convDao, never()).findById(1L, 0L);
    }

    /** 会话详情种子：命中快照时解析成 VO 列表。 */
    @Test
    void snapshotReturnsParsedCommands() {
        when(redis.getString("autowonder:clarification:commands:v1:42"))
                .thenReturn("{\"availableCommands\":[{\"name\":\"quest\",\"description\":\"d\"}]}");

        var list = service.snapshot(1L, 42L);

        assertEquals(1, list.size());
        assertEquals("quest", list.get(0).getName());
    }

    /** 无缓存时返回空列表而非 null，前端据此判断需要触发探针。 */
    @Test
    void snapshotEmptyWhenNoCache() {
        when(redis.getString(anyString())).thenReturn(null);

        assertTrue(service.snapshot(1L, 42L).isEmpty());
    }

    /** Redis 读故障只损失秒显种子：降级为空列表，绝不把异常抛给会话详情（工单 55411 修复要求 3）。 */
    @Test
    void snapshotDegradesToEmptyListWhenRedisReadFails() {
        when(redis.getString(anyString())).thenThrow(new RuntimeException("redis down"));

        assertTrue(service.snapshot(1L, 42L).isEmpty());
    }

    /** Redis 写失败连累不到浏览器：实时命令推送仍要发出，两者是独立的辅助动作。 */
    @Test
    void onResultStillPublishesToBrowserWhenRedisWriteFails() {
        String cmds = "{\"availableCommands\":[{\"name\":\"quest\"}]}";
        when(convDao.findById(1L, 42L)).thenReturn(conv());
        doThrow(new RuntimeException("redis down"))
                .when(redis).setWithExpire(anyString(), anyString(), anyLong());

        service.onResult(1L, 7L, 42L, "OK", cmds, "");

        verify(publisher).publishServerEvent(eq(1L), eq(42L), eq(0L), eq("acp_commands"),
                eq("{\"type\":\"acp_commands\",\"data\":" + cmds + "}"));
    }

    /** 浏览器推送失败也只丢实时命令，快照已落库，不向执行器帧处理链路抛异常。 */
    @Test
    void onResultDegradesWhenBrowserPublishFails() {
        when(convDao.findById(1L, 42L)).thenReturn(conv());
        doThrow(new RuntimeException("push failed"))
                .when(publisher).publishServerEvent(anyLong(), anyLong(), anyLong(), anyString(),
                        anyString());

        service.onResult(1L, 7L, 42L, "OK", "{\"availableCommands\":[{\"name\":\"quest\"}]}", "");

        verify(redis).setWithExpire(eq("autowonder:clarification:commands:v1:42"), anyString(),
                anyLong());
    }

    /** 抢锁时 Redis 故障无法判断是否有探针在途：放弃本次探针并降级，不让接口失败。 */
    @Test
    void refreshSkipsProbeWhenRedisLockFails() {
        when(convDao.findById(1L, 42L)).thenReturn(conv());
        when(redis.setIfAbsent(anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));

        service.refresh(1L, 42L);

        verify(transport, never()).sendCommandsProbe(any());
    }
}
