package com.aliyun.autowonder.integration.receipt;

import com.aliyun.autowonder.integration.AoneIntegrationService;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDO;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDao;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;
import com.aliyun.autowonder.integration.provider.ExternalComment;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AoneOperationReadbackHandlerTest {

    @Test
    void commentMarkerConfirmsTimeoutAfterSuccessWithoutReplaying() {
        Fixture fixture = fixture();
        ExternalComment comment = new ExternalComment();
        comment.setExternalId("comment-8");
        comment.setContentMd("body\n\n<!-- aw-op:0123456789abcdef -->");
        when(fixture.provider.listComments(any(), eq(List.of("workitem-9"))))
                .thenReturn(List.of(comment));
        IntegrationOutboxDO receipt = receipt("COMMENT_CREATE",
                "{\"externalWorkitemId\":\"workitem-9\","
                        + "\"commentId\":88,"
                        + "\"marker\":\"<!-- aw-op:0123456789abcdef -->\","
                        + "\"contentDigest\":\"unused\"}");

        ExternalOperationReadbackHandler.ReadbackResult result = fixture.handler.readback(receipt);

        assertEquals(ExternalOperationReadbackHandler.Outcome.FOUND, result.outcome());
        verify(fixture.commentLinkDao).insert(any(ExternalCommentLinkDO.class));
    }

    @Test
    void statusAndContentEventsAreNotAutomaticallyRecoverable() {
        Fixture fixture = fixture();
        ExternalOperationReadbackHandler.ReadbackResult status = fixture.handler.readback(
                receipt("STATUS_UPDATE", "{\"externalWorkitemId\":\"workitem-9\","
                        + "\"externalStatusName\":\"Closed\"}"));
        ExternalOperationReadbackHandler.ReadbackResult content = fixture.handler.readback(
                receipt("CONTENT_UPDATE", "{\"externalWorkitemId\":\"workitem-9\"}"));

        assertEquals(ExternalOperationReadbackHandler.Outcome.UNAVAILABLE, status.outcome());
        assertEquals(ExternalOperationReadbackHandler.Outcome.UNAVAILABLE, content.outcome());
        verify(fixture.provider, never()).getWorkitem(any(), any());
    }

    @Test
    void readApiFailureIsUnavailableRatherThanDefinitelyNotFound() {
        Fixture fixture = fixture();
        when(fixture.provider.listComments(any(), eq(List.of("workitem-9"))))
                .thenThrow(new IllegalStateException("readback endpoint unavailable"));

        ExternalOperationReadbackHandler.ReadbackResult result = fixture.handler.readback(
                receipt("COMMENT_CREATE",
                        "{\"externalWorkitemId\":\"workitem-9\","
                                + "\"commentId\":88,\"marker\":\"m\",\"contentDigest\":\"body\"}"));

        assertEquals(ExternalOperationReadbackHandler.Outcome.UNAVAILABLE, result.outcome());
    }

    private Fixture fixture() {
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setId(3L);
        binding.setTenantId(7L);
        binding.setProvider(AoneIntegrationService.PROVIDER);
        binding.setBaseUrl("https://integration.example.invalid");
        binding.setClientKey("client");
        binding.setCredentialRef("credential-ref");
        binding.setRegionId("region");
        when(bindingDao.findById(3L)).thenReturn(binding);
        when(provider.provider()).thenReturn(AoneIntegrationService.PROVIDER);
        when(secretCrypto.decrypt("credential-ref")).thenReturn("secret");
        return new Fixture(provider, commentLinkDao,
                new AoneOperationReadbackHandler(bindingDao, commentLinkDao,
                        List.of(provider), secretCrypto));
    }

    private IntegrationOutboxDO receipt(String eventType, String payloadJson) {
        IntegrationOutboxDO receipt = new IntegrationOutboxDO();
        receipt.setProvider(AoneIntegrationService.PROVIDER);
        receipt.setId(1L);
        receipt.setTenantId(7L);
        receipt.setBindingId(3L);
        receipt.setWorkitemId(11L);
        receipt.setEventType(eventType);
        receipt.setPayloadJson(payloadJson);
        return receipt;
    }

    private record Fixture(ExternalWorkitemProvider provider, ExternalCommentLinkDao commentLinkDao,
                           AoneOperationReadbackHandler handler) {
    }
}
