package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.*;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.*;
import com.aliyun.autowonder.executor.*;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.PresenceManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real routing/registry regression: terminal ACK need not refresh the capacity snapshot. */
class ConversationCapacityAdmissionTest {
    final RedisManager redis = mock(RedisManager.class);
    final PresenceManager presence = mock(PresenceManager.class);
    final ExecutorRegistry registry = new ExecutorRegistry(redis);
    final ExecutorSelector selector = new ExecutorSelector(redis, registry, presence, mock(DispatchDao.class));
    final AgentEnvironmentVariableRefDao refs = mock(AgentEnvironmentVariableRefDao.class);
    final ConversationExecutorRouter router = new ConversationExecutorRouter(refs, selector);
    final AgentConversationDao conversations = mock(AgentConversationDao.class);
    final AgentConversationTurnDao turns = mock(AgentConversationTurnDao.class);
    final ConversationTransport transport = mock(ConversationTransport.class);
    final AgentDao agents = mock(AgentDao.class);
    final AgentVersionDao versions = mock(AgentVersionDao.class);
    final AgentConversationService service = new AgentConversationService(conversations, turns, transport,
            agents, versions, mock(ConversationChannelSinkRegistry.class), router);
    final AgentConversationDO conversation = new AgentConversationDO();
    final AtomicReference<ExecutorDispatchSnapshot> inventory = new AtomicReference<>();
    final AtomicReference<AgentConversationTurnDO> stored = new AtomicReference<>();

    @BeforeEach void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(redis.smembers(ExecutorSelector.execsKey(3L))).thenReturn(Set.of("9"));
        when(redis.exists(ExecutorRegistry.onlineKey(9L))).thenReturn(true);
        when(redis.getString("exec:session:9")).thenReturn("current-session");
        when(redis.get(ExecutorDispatchSnapshot.key(9L))).thenAnswer(i -> inventory.get());
        when(presence.supportsProtocolFeature(eq(9L), anyString())).thenReturn(true);
        inventory.set(snapshot(1, true, true, null, Set.of(55L)));
        var agent = new AgentDO(); agent.setId(3L); agent.setTenantId(1L); agent.setOnlineVersionId(50L);
        when(agents.findById(3L)).thenReturn(agent);
        var version = new AgentVersionDO(); version.setRoleName("Test agent");
        when(versions.findById(50L)).thenReturn(version);
        conversation.setId(77L); conversation.setTenantId(1L); conversation.setAgentId(3L);
        conversation.setOwnerUserId(42L);
        conversation.setExecutorId(9L); conversation.setAgentVersionId(50L);
        conversation.setChannelConversationId("existing"); conversation.setStatus("ACTIVE");
        conversation.setBizRefType("WORKITEM"); conversation.setBizRefId(10011L);
        when(conversations.findById(1L, 77L)).thenReturn(conversation);
        when(conversations.findByKey(eq(1L), anyString(), eq("existing"), eq(3L))).thenReturn(conversation);
        when(turns.acquireConversationLock(anyString(), anyInt())).thenReturn(1);
        when(turns.releaseConversationLock(anyString())).thenReturn(1);
        when(turns.insert(any())).thenAnswer(i -> {
            AgentConversationTurnDO turn = i.getArgument(0); turn.setId(90L); stored.set(turn); return 1;
        });
        when(turns.findByExternalMsgId(1L, "web:77:continuation")).thenAnswer(i -> stored.get());
        when(turns.findNextQueuedInbound(1L, 77L)).thenAnswer(i ->
                stored.get() != null && "QUEUED".equals(stored.get().getStatus()) ? stored.get() : null);
        when(turns.findProcessingInbound(1L, 77L)).thenAnswer(i ->
                stored.get() != null && "PROCESSING".equals(stored.get().getStatus()) ? stored.get() : null);
        when(turns.listQueuedInboundAfter(anyLong(), eq(100))).thenAnswer(i ->
                stored.get() != null && "QUEUED".equals(stored.get().getStatus()) ? List.of(stored.get()) : List.of());
        when(turns.updateStatusIfCurrent(1L, 90L, "QUEUED", "PROCESSING", null)).thenAnswer(i -> {
            if (stored.get() == null || !"QUEUED".equals(stored.get().getStatus())) return 0;
            stored.get().setStatus("PROCESSING"); return 1;
        });
        when(turns.recordDispatchAttemptIfProcessing(1L, 77L, 90L)).thenReturn(1);
    }

    @AfterEach void cleanUp() throws Exception {
        TransactionSynchronizationManager.getSynchronizations().forEach(s ->
                s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        var field = AgentConversationService.class.getDeclaredField("cancelTimeoutScheduler"); field.setAccessible(true);
        ((java.util.concurrent.ScheduledExecutorService) field.get(service)).shutdownNow();
    }

    ExecutorDispatchSnapshot snapshot(int capacity, boolean authoritative, boolean ready, String error, Set<Long> busy) {
        return new ExecutorDispatchSnapshot("current-session", capacity, authoritative, ready,
                Set.of(), Set.of(), busy, Set.of(ConversationProtocolFeatures.TURN_EVENT), error, 1L);
    }

    void submit(String channel, Long actor) {
        conversation.setChannel(channel);
        service.submitTurn(1L, 3L, channel, "existing", "Continue with my explicit answer",
                "web:77:continuation", null, actor);
    }

    void commit() {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        callbacks.forEach(TransactionSynchronization::afterCommit);
        callbacks.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
    }

    @ParameterizedTest @ValueSource(strings = {"WORKITEM_CLARIFICATION", "PLATFORM_ASSISTANT"})
    void existingWebConversationAcceptsTurnWhileOnlineOwnerSnapshotStillHasOneComputingTurn(String channel) {
        assertTrue(registry.isAvailable(9L));
        assertNull(selector.select(3L, 9L), "maxTasks=1 must still prevent immediate dispatch");
        assertDoesNotThrow(() -> submit(channel, 42L));
        assertEquals("QUEUED", stored.get().getStatus());
        assertEquals("IN", stored.get().getDirection());
        assertEquals(42L, stored.get().getSubmittedUserId());
        assertEquals(77L, stored.get().getConversationId());
        assertEquals("web:77:continuation", stored.get().getExternalMsgId());
        assertEquals("Continue with my explicit answer", stored.get().getContent());
        verify(turns, never()).recordDispatchAttemptIfProcessing(anyLong(), anyLong(), anyLong());
        verify(conversations, never()).updateExecutor(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(transport);
        commit();

        submit(channel, 42L); // same actor's retry is durable idempotency, not another queued IN
        assertThrows(BizException.class, () -> submit(channel, 43L));
        verify(turns, times(1)).insert(any());
        service.recoverQueuedTurns(); commit();
        assertEquals("QUEUED", stored.get().getStatus());
        verifyNoInteractions(transport);

        // Fresh heartbeat is the only capacity change; never decrement occupancy on ACK.
        inventory.set(snapshot(1, true, true, null, Set.of()));
        service.recoverQueuedTurns();
        verifyNoInteractions(transport); // transport still waits for commit
        commit();
        assertEquals("PROCESSING", stored.get().getStatus());
        service.recoverQueuedTurns(); commit();
        verify(transport, times(1)).send(eq(conversation), eq(90L),
                argThat(body -> body.endsWith("Continue with my explicit answer")
                        && (!"PLATFORM_ASSISTANT".equals(channel) || body.contains("userId=42"))), any(), eq(1));
        verify(turns, times(1)).recordDispatchAttemptIfProcessing(1L, 77L, 90L);
        assertEquals(1, inventory.get().capacity());
    }

    @ParameterizedTest @ValueSource(strings = {"offline", "deleted", "cooldown", "member", "nullOwner",
            "snapshot", "session", "closed", "recovering", "inventoryError", "zeroCapacity", "protocol", "environment"})
    void ownerEligibilityRejectsNonCapacityFailures(String failure) {
        switch (failure) {
            case "offline" -> when(redis.exists(ExecutorRegistry.onlineKey(9L))).thenReturn(false);
            case "deleted" -> when(redis.exists(ExecutorRegistry.deletedKey(9L))).thenReturn(true);
            case "cooldown" -> {
                when(redis.exists(ExecutorRegistry.providerCooldownKey(9L))).thenReturn(true);
                when(redis.getString(ExecutorRegistry.providerCooldownKey(9L))).thenReturn("failover:test");
            }
            case "member" -> when(redis.smembers(ExecutorSelector.execsKey(3L))).thenReturn(Set.of("10"));
            case "nullOwner" -> conversation.setExecutorId(null);
            case "snapshot" -> inventory.set(null);
            case "session" -> when(redis.getString("exec:session:9")).thenReturn("replacement-session");
            case "closed" -> when(redis.exists(ExecutorDispatchSnapshot.closedSessionKey(9L, "current-session"))).thenReturn(true);
            case "recovering" -> inventory.set(snapshot(1, true, false, null, Set.of(55L)));
            case "inventoryError" -> inventory.set(snapshot(1, true, true, "recovery failed", Set.of(55L)));
            case "zeroCapacity" -> inventory.set(snapshot(0, true, true, null, Set.of()));
            case "protocol" -> when(presence.supportsProtocolFeature(9L, ConversationProtocolFeatures.TURN_EVENT)).thenReturn(false);
            case "environment" -> {
                when(refs.listByVersion(1L, 50L)).thenReturn(List.of(new AgentEnvironmentVariableRefDO()));
                when(presence.supportsProtocolFeature(9L, ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1)).thenReturn(false);
            }
        }
        assertThrows(RuntimeException.class, () -> submit("PLATFORM_ASSISTANT", 42L));
        verify(turns, never()).insert(any());
        verifyNoInteractions(transport);
    }

    @Test void selectorExceptionsAreNotConvertedToQueueAdmission() {
        when(redis.smembers(ExecutorSelector.execsKey(3L))).thenThrow(new IllegalStateException("selection unavailable"));
        var failure = assertThrows(IllegalStateException.class, () -> submit("WORKITEM_CLARIFICATION", 42L));
        assertEquals("selection unavailable", failure.getMessage());
        verify(turns, never()).insert(any());
    }

    @ParameterizedTest @ValueSource(strings = {"DINGTALK", "SQUAD_WORKFLOW"})
    void nonWebChannelsKeepCapacityGateEvenWithActor(String channel) {
        assertThrows(IllegalStateException.class, () -> submit(channel, 42L));
        verify(turns, never()).insert(any());
    }

    @Test void legacyWebOverloadCannotOptIntoCapacityAdmissionWithoutTrustedActor() {
        conversation.setChannel("WORKITEM_CLARIFICATION");
        assertThrows(IllegalStateException.class, () -> service.submitTurn(1L, 3L,
                "WORKITEM_CLARIFICATION", "existing", "answer", "legacy", "{\"submittedUserId\":42}"));
        verify(turns, never()).insert(any());
    }

    @ParameterizedTest @ValueSource(strings = {"WORKITEM_CLARIFICATION", "PLATFORM_ASSISTANT"})
    void absentLogicalConversationStillRequiresCapacity(String channel) {
        when(conversations.findByKey(1L, channel, "existing", 3L)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> submit(channel, 42L));
        verify(conversations, never()).insert(any()); verify(turns, never()).insert(any());
    }

    @Test void legacyNonAuthoritativeInventoryKeepsItsExistingCompatibility() {
        inventory.set(snapshot(1, false, false, null, Set.of(55L)));
        assertDoesNotThrow(() -> submit("WORKITEM_CLARIFICATION", 42L));
        assertEquals("QUEUED", stored.get().getStatus());
    }

    @Test void freeCapacityDispatchesImmediatelyAfterCommit() {
        inventory.set(snapshot(1, true, true, null, Set.of()));
        submit("WORKITEM_CLARIFICATION", 42L);
        assertEquals("PROCESSING", stored.get().getStatus());
        verifyNoInteractions(transport); commit();
        verify(transport).send(eq(conversation), eq(90L), anyString(), any(), eq(1));
    }
    @ParameterizedTest @ValueSource(strings = {"success", "unauthorized", "bindRace"})
    void clarificationAttachmentAdmissionCommitsOrRollsBackWithTheQueuedTurn(String outcome) {
        // An isolated in-memory transaction proves persistence rollback, not just callback suppression.
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        var dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:capacity_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE admitted_turn (id BIGINT PRIMARY KEY, status VARCHAR(32))");
        var tx = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        var attachments = mock(ClarificationAttachmentService.class);
        when(attachments.requireBindableIds(1L, 77L, 42L, List.of(5L))).thenReturn(List.of(5L));
        if ("unauthorized".equals(outcome)) {
            when(attachments.requireBindableIds(1L, 77L, 42L, List.of(5L)))
                    .thenThrow(new IllegalArgumentException("attachment owner mismatch"));
        }
        if ("bindRace".equals(outcome)) {
            doThrow(new IllegalArgumentException("attachment concurrently consumed")).when(attachments)
                    .bindToTurn(1L, 77L, 42L, List.of(5L), 90L);
        }
        doAnswer(i -> {
            AgentConversationTurnDO turn = i.getArgument(0); turn.setId(90L); stored.set(turn);
            return jdbc.update("INSERT INTO admitted_turn VALUES (?, ?)", turn.getId(), turn.getStatus());
        }).when(turns).insert(any());
        when(turns.findByExternalMsgId(1L, "web-clarification:77:attachment"))
                .thenAnswer(i -> stored.get());
        var wrapper = new WorkitemClarificationConversationService(conversations, turns, service,
                agents, mock(ConversationRuntimePresence.class), mock(ConversationElicitationService.class),
                mock(ConversationCommandsService.class), router, attachments);
        conversation.setChannel("WORKITEM_CLARIFICATION");
        try {
            Runnable send = () -> tx.executeWithoutResult(status -> wrapper.submitTurn(1L, 10011L,
                    77L, 42L, "answer with attachment", "attachment", List.of(5L)));
            if ("success".equals(outcome)) {
                send.run();
                assertEquals("QUEUED", jdbc.queryForObject("SELECT status FROM admitted_turn", String.class));
                verify(attachments).bindToTurn(1L, 77L, 42L, List.of(5L), 90L);
            } else {
                assertThrows(IllegalArgumentException.class, send::run);
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM admitted_turn", Integer.class));
                if ("bindRace".equals(outcome)) verify(turns).insert(any());
                else verify(turns, never()).insert(any());
            }
            verifyNoInteractions(transport);
            verify(conversations, never()).updateExecutor(anyLong(), anyLong(), anyLong());
        } finally {
            jdbc.execute("SHUTDOWN");
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
        }
    }

}
