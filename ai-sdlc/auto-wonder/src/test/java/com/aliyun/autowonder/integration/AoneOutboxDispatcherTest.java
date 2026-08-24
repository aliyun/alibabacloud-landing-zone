package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.integration.aone.AoneOpenApiException;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDao;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDao;
import com.aliyun.autowonder.integration.generic.GenericHttpWorkitemWritebackProvider;
import com.aliyun.autowonder.integration.provider.ExternalComment;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AoneOutboxDispatcherTest {

    @Test
    void concurrentDispatchCandidatesProduceOnlyOneExternalWrite() {
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(workitemProvider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        AoneOutboxDispatcher dispatcher = new AoneOutboxDispatcher(outboxDao, bindingDao,
                mock(ExternalCommentLinkDao.class), List.of(workitemProvider), secretCrypto,
                mock(GenericHttpWorkitemWritebackProvider.class));
        IntegrationOutboxDO outbox = contentOutbox();
        ExternalProjectBindingDO binding = binding();
        binding.setWritebackStaffId("WORKER_1");
        when(outboxDao.listPendingAny(20)).thenReturn(List.of(outbox, outbox));
        when(outboxDao.markSending(11L, 0L)).thenReturn(1);
        when(outboxDao.markSucceeded(any(), anyLong())).thenReturn(1);
        when(bindingDao.findById(1L)).thenReturn(binding);
        when(secretCrypto.decrypt("secret-ref")).thenReturn("secret");

        assertEquals(1, dispatcher.dispatchPending(20));

        verify(workitemProvider, times(1)).updateContent(any(), eq("59066940"),
                eq("WORKER_1"), eq("新标题"), eq("新正文"));
    }

    @Test
    void timeoutAfterProviderMayHaveSucceededBecomesUnknownInsteadOfRetry() {
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(workitemProvider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        AoneOutboxDispatcher dispatcher = new AoneOutboxDispatcher(outboxDao, bindingDao,
                mock(ExternalCommentLinkDao.class), List.of(workitemProvider), secretCrypto,
                mock(GenericHttpWorkitemWritebackProvider.class));
        IntegrationOutboxDO outbox = contentOutbox();
        ExternalProjectBindingDO binding = binding();
        binding.setWritebackStaffId("WORKER_1");
        ready(outboxDao, outbox);
        when(bindingDao.findById(1L)).thenReturn(binding);
        when(secretCrypto.decrypt("secret-ref")).thenReturn("secret");
        doThrow(new IllegalStateException("timeout", new SocketTimeoutException("response lost")))
                .when(workitemProvider).updateContent(any(), any(), any(), any(), any());

        assertEquals(0, dispatcher.dispatchPending(20));

        verify(outboxDao).markUnknown(eq(11L), eq(1L), eq("timeout"));
        verify(outboxDao, never()).markFailed(any(), anyLong(), any(Boolean.class), any());
    }

    @Test
    void localAckFailureAfterExternalCommentSuccessBecomesUnknown() {
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(workitemProvider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        AoneOutboxDispatcher dispatcher = new AoneOutboxDispatcher(outboxDao, bindingDao,
                commentLinkDao, List.of(workitemProvider), secretCrypto,
                mock(GenericHttpWorkitemWritebackProvider.class));
        IntegrationOutboxDO outbox = commentOutbox();
        ExternalProjectBindingDO binding = binding();
        binding.setWritebackStaffId("WORKER_1");
        ExternalComment externalComment = new ExternalComment();
        externalComment.setExternalId("external-comment");
        ready(outboxDao, outbox);
        when(bindingDao.findById(1L)).thenReturn(binding);
        when(secretCrypto.decrypt("secret-ref")).thenReturn("secret");
        when(workitemProvider.createComment(any(), any(), any(), any())).thenReturn(externalComment);
        doThrow(new IllegalStateException("local receipt link unavailable"))
                .when(commentLinkDao).insert(any());

        assertEquals(0, dispatcher.dispatchPending(20));

        verify(outboxDao).markUnknown(10L, 1L, "local receipt link unavailable");
        verify(outboxDao, never()).markFailed(any(), anyLong(), any(Boolean.class), any());
    }

    @Test
    void commentWritebackWithMissingStaffIdFailsRetryableWithoutCallingProvider() {
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        when(workitemProvider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        AoneOutboxDispatcher dispatcher = new AoneOutboxDispatcher(outboxDao, bindingDao,
                mock(ExternalCommentLinkDao.class), List.of(workitemProvider), mock(SecretCrypto.class),
                mock(GenericHttpWorkitemWritebackProvider.class));
        IntegrationOutboxDO outbox = commentOutbox();
        ExternalProjectBindingDO binding = binding();
        binding.setWritebackStaffId(" ");

        ready(outboxDao, outbox);
        when(bindingDao.findById(1L)).thenReturn(binding);

        int success = dispatcher.dispatchPending(20);

        assertEquals(0, success);
        verify(outboxDao).markFailed(10L, 1L, true, "Aone writeback staffId is required");
        verify(workitemProvider, never()).createComment(any(), eq("59066940"), any(), any());
    }

    @Test
    void aoneContentUpdateWithMissingStaffIdFailsRetryableWithoutCallingProvider() {
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        when(workitemProvider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        AoneOutboxDispatcher dispatcher = new AoneOutboxDispatcher(outboxDao, bindingDao,
                mock(ExternalCommentLinkDao.class), List.of(workitemProvider), mock(SecretCrypto.class),
                mock(GenericHttpWorkitemWritebackProvider.class));
        IntegrationOutboxDO outbox = contentOutbox();
        ExternalProjectBindingDO binding = binding();
        binding.setWritebackStaffId(" ");

        ready(outboxDao, outbox);
        when(bindingDao.findById(1L)).thenReturn(binding);

        int success = dispatcher.dispatchPending(20);

        assertEquals(0, success);
        verify(outboxDao).markFailed(11L, 1L, true, "Aone writeback staffId is required");
        verify(workitemProvider, never()).updateContent(any(), eq("59066940"), any(), any(), any());
    }

    @Test
    void contentUpdateOutboxCallsProviderAndMarksSucceeded() {
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(workitemProvider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        AoneOutboxDispatcher dispatcher = new AoneOutboxDispatcher(outboxDao, bindingDao,
                mock(ExternalCommentLinkDao.class), List.of(workitemProvider), secretCrypto,
                mock(GenericHttpWorkitemWritebackProvider.class));
        IntegrationOutboxDO outbox = contentOutbox();
        ExternalProjectBindingDO binding = binding();
        binding.setWritebackStaffId("WORKER_1782377321313");

        ready(outboxDao, outbox);
        when(bindingDao.findById(1L)).thenReturn(binding);
        when(secretCrypto.decrypt("secret-ref")).thenReturn("secret");

        int success = dispatcher.dispatchPending(20);

        assertEquals(1, success);
        verify(workitemProvider).updateContent(any(), eq("59066940"), eq("WORKER_1782377321313"),
                eq("新标题"), eq("新正文"));
        verify(outboxDao).markSucceeded(11L, 1L);
    }

    @Test
    void nonAoneContentUpdateOutboxCallsOriginalProviderAndMarksSucceeded() {
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider aoneProvider = mock(ExternalWorkitemProvider.class);
        ExternalWorkitemProvider jiraProvider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(aoneProvider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        when(jiraProvider.provider()).thenReturn("JIRA");
        AoneOutboxDispatcher dispatcher = new AoneOutboxDispatcher(outboxDao, bindingDao,
                mock(ExternalCommentLinkDao.class), List.of(aoneProvider, jiraProvider), secretCrypto,
                mock(GenericHttpWorkitemWritebackProvider.class));
        IntegrationOutboxDO outbox = contentOutbox("JIRA");
        ExternalProjectBindingDO binding = binding("JIRA");

        ready(outboxDao, outbox);
        when(bindingDao.findById(1L)).thenReturn(binding);
        when(secretCrypto.decrypt("secret-ref")).thenReturn("secret");

        int success = dispatcher.dispatchPending(20);

        assertEquals(1, success);
        verify(jiraProvider).updateContent(any(), eq("59066940"), isNull(),
                eq("新标题"), eq("新正文"));
        verify(aoneProvider, never()).updateContent(any(), any(), any(), any(), any());
        verify(outboxDao).markSucceeded(11L, 1L);
    }

    @Test
    void nonAoneContentUpdateWithoutSpecificProviderUsesGenericWritebackAndMarksSucceeded() {
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider aoneProvider = mock(ExternalWorkitemProvider.class);
        GenericHttpWorkitemWritebackProvider genericProvider = mock(GenericHttpWorkitemWritebackProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(aoneProvider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        AoneOutboxDispatcher dispatcher = new AoneOutboxDispatcher(outboxDao, bindingDao,
                mock(ExternalCommentLinkDao.class), List.of(aoneProvider), secretCrypto, genericProvider);
        IntegrationOutboxDO outbox = contentOutbox("JIRA");
        ExternalProjectBindingDO binding = binding("JIRA");

        ready(outboxDao, outbox);
        when(bindingDao.findById(1L)).thenReturn(binding);
        when(secretCrypto.decrypt("secret-ref")).thenReturn("secret");

        int success = dispatcher.dispatchPending(20);

        assertEquals(1, success);
        verify(genericProvider).updateContent(eq("JIRA"), any(), eq("59066940"),
                eq("新标题"), eq("新正文"));
        verify(aoneProvider, never()).updateContent(any(), any(), any(), any(), any());
        verify(outboxDao).markSucceeded(11L, 1L);
    }

    @Test
    void genericContentWritebackAllowsBlankCredentialRef() {
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider aoneProvider = mock(ExternalWorkitemProvider.class);
        GenericHttpWorkitemWritebackProvider genericProvider = mock(GenericHttpWorkitemWritebackProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(aoneProvider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        AoneOutboxDispatcher dispatcher = new AoneOutboxDispatcher(outboxDao, bindingDao,
                mock(ExternalCommentLinkDao.class), List.of(aoneProvider), secretCrypto, genericProvider);
        IntegrationOutboxDO outbox = contentOutbox("JIRA");
        ExternalProjectBindingDO binding = binding("JIRA");
        binding.setCredentialRef(null);

        ready(outboxDao, outbox);
        when(bindingDao.findById(1L)).thenReturn(binding);

        int success = dispatcher.dispatchPending(20);

        assertEquals(1, success);
        verify(secretCrypto, never()).decrypt(any());
        verify(genericProvider).updateContent(eq("JIRA"), any(), eq("59066940"),
                eq("新标题"), eq("新正文"));
        verify(outboxDao).markSucceeded(11L, 1L);
    }

    @Test
    void terminalProviderErrorFailsPermanentImmediately() {
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(workitemProvider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        AoneOutboxDispatcher dispatcher = new AoneOutboxDispatcher(outboxDao, bindingDao,
                mock(ExternalCommentLinkDao.class), List.of(workitemProvider), secretCrypto,
                mock(GenericHttpWorkitemWritebackProvider.class));
        IntegrationOutboxDO outbox = contentOutbox();
        ExternalProjectBindingDO binding = binding();
        binding.setWritebackStaffId("WORKER_1");

        ready(outboxDao, outbox);
        when(bindingDao.findById(1L)).thenReturn(binding);
        when(secretCrypto.decrypt("secret-ref")).thenReturn("secret");
        doThrow(new AoneOpenApiException("状态流转限制,不能将状态置为Closed", true))
                .when(workitemProvider).updateContent(any(), eq("59066940"), any(), any(), any());

        int success = dispatcher.dispatchPending(20);

        assertEquals(0, success);
        verify(outboxDao).markFailed(eq(11L), eq(1L), eq(false), any());
    }

    @Test
    void transientProviderErrorBelowRetryCapStaysRetryable() {
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(workitemProvider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        AoneOutboxDispatcher dispatcher = new AoneOutboxDispatcher(outboxDao, bindingDao,
                mock(ExternalCommentLinkDao.class), List.of(workitemProvider), secretCrypto,
                mock(GenericHttpWorkitemWritebackProvider.class));
        IntegrationOutboxDO outbox = contentOutbox();
        outbox.setRetryCount(0);
        ExternalProjectBindingDO binding = binding();
        binding.setWritebackStaffId("WORKER_1");

        ready(outboxDao, outbox);
        when(bindingDao.findById(1L)).thenReturn(binding);
        when(secretCrypto.decrypt("secret-ref")).thenReturn("secret");
        doThrow(new AoneOpenApiException("over rate limit. rate limit is 100"))
                .when(workitemProvider).updateContent(any(), eq("59066940"), any(), any(), any());

        int success = dispatcher.dispatchPending(20);

        assertEquals(0, success);
        verify(outboxDao).markFailed(eq(11L), eq(1L), eq(true), any());
    }

    @Test
    void transientProviderErrorAtRetryCapFailsPermanent() {
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(workitemProvider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        AoneOutboxDispatcher dispatcher = new AoneOutboxDispatcher(outboxDao, bindingDao,
                mock(ExternalCommentLinkDao.class), List.of(workitemProvider), secretCrypto,
                mock(GenericHttpWorkitemWritebackProvider.class));
        IntegrationOutboxDO outbox = contentOutbox();
        outbox.setRetryCount(9);
        ExternalProjectBindingDO binding = binding();
        binding.setWritebackStaffId("WORKER_1");

        ready(outboxDao, outbox);
        when(bindingDao.findById(1L)).thenReturn(binding);
        when(secretCrypto.decrypt("secret-ref")).thenReturn("secret");
        doThrow(new AoneOpenApiException("over rate limit. rate limit is 100"))
                .when(workitemProvider).updateContent(any(), eq("59066940"), any(), any(), any());

        int success = dispatcher.dispatchPending(20);

        assertEquals(0, success);
        verify(outboxDao).markFailed(eq(11L), eq(1L), eq(false), any());
    }

    private IntegrationOutboxDO commentOutbox() {
        IntegrationOutboxDO outbox = new IntegrationOutboxDO();
        outbox.setId(10L);
        outbox.setTenantId(10002L);
        outbox.setProvider(AoneIntegrationService.PROVIDER);
        outbox.setBindingId(1L);
        outbox.setWorkitemId(10356L);
        outbox.setEventType("COMMENT_CREATE");
        outbox.setLockVersion(0L);
        outbox.setPayloadJson("{\"externalWorkitemId\":\"59066940\",\"commentId\":10849,\"contentMd\":\"hello\"}");
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        return outbox;
    }

    private IntegrationOutboxDO contentOutbox() {
        return contentOutbox(AoneIntegrationService.PROVIDER);
    }

    private IntegrationOutboxDO contentOutbox(String provider) {
        IntegrationOutboxDO outbox = new IntegrationOutboxDO();
        outbox.setId(11L);
        outbox.setTenantId(10002L);
        outbox.setProvider(provider);
        outbox.setBindingId(1L);
        outbox.setWorkitemId(10356L);
        outbox.setEventType("CONTENT_UPDATE");
        outbox.setLockVersion(0L);
        outbox.setPayloadJson("{\"externalWorkitemId\":\"59066940\",\"title\":\"新标题\",\"contentMd\":\"新正文\"}");
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        return outbox;
    }

    private ExternalProjectBindingDO binding() {
        return binding(AoneIntegrationService.PROVIDER);
    }

    private ExternalProjectBindingDO binding(String provider) {
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setId(1L);
        binding.setTenantId(10002L);
        binding.setProvider(provider);
        binding.setBaseUrl("http://aone-api.alibaba-inc.com");
        binding.setClientKey("auto-wonder");
        binding.setCredentialRef("secret-ref");
        binding.setRegionId("1");
        return binding;
    }

    private void ready(IntegrationOutboxDao outboxDao, IntegrationOutboxDO outbox) {
        when(outboxDao.listPendingAny(20)).thenReturn(List.of(outbox));
        when(outboxDao.markSending(outbox.getId(), outbox.getLockVersion())).thenReturn(1);
        when(outboxDao.markSucceeded(any(), anyLong())).thenReturn(1);
    }
}
