package com.aliyun.autowonder.websocket;

import org.junit.jupiter.api.Test;

import javax.websocket.Session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExecutorWsEndpointTest {

    @Test
    void configuresTextMessageBufferAboveRuntimeFrameBudget() {
        Session session = mock(Session.class);

        ExecutorWsEndpoint.configureMessageLimits(session);

        verify(session).setMaxTextMessageBufferSize(256 * 1024);
    }

    @Test
    void isolatesInboundFrameRoutingFailureFromWebSocketContainer() {
        ExecutorSession executor = mock(ExecutorSession.class);
        InboundFrameRouter router = mock(InboundFrameRouter.class);
        doThrow(new IllegalStateException("temporary workflow failure"))
                .when(router).route(executor, "result");

        assertDoesNotThrow(() -> ExecutorWsEndpoint.routeSafely(executor, "result", router));

        verify(router).route(executor, "result");
    }
}
