package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.environment.AgentEnvironmentVariableResolver;
import com.aliyun.autowonder.websocket.ExecutorWsAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DaemonExecutorEnvironmentControllerTest {

    private ExecutorWsAuthenticator authenticator;
    private AgentDao agentDao;
    private AgentEnvironmentVariableResolver resolver;
    private DaemonExecutorEnvironmentController controller;

    @BeforeEach
    void setUp() {
        authenticator = mock(ExecutorWsAuthenticator.class);
        agentDao = mock(AgentDao.class);
        resolver = mock(AgentEnvironmentVariableResolver.class);
        controller = new DaemonExecutorEnvironmentController(authenticator, agentDao, resolver);
    }

    @Test
    void returnsCurrentOnlineEnvironmentSnapshotWithoutCaching() {
        stubAuthentication("executor-token", true, 51L, 8L);
        when(agentDao.findById(51L)).thenReturn(agent(51L, 8L, 101L));
        when(resolver.resolve(8L, 101L)).thenReturn(Map.of("TOKEN", "secret"));

        ResponseEntity<?> response = controller.environmentVariables(17L, "Bearer executor-token");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEnvironmentVariables(response, Map.of("TOKEN", "secret"));
    }

    @Test
    void returnsExplicitEmptySnapshotWhenAgentHasNoOnlineVersion() {
        stubAuthentication("executor-token", true, 51L, 8L);
        when(agentDao.findById(51L)).thenReturn(agent(51L, 8L, null));

        ResponseEntity<?> response = controller.environmentVariables(17L, "Bearer executor-token");

        assertEquals(200, response.getStatusCode().value());
        assertEnvironmentVariables(response, Map.of());
        verifyNoInteractions(resolver);
    }

    @Test
    void rejectsMissingAndMalformedAuthorizationWithoutAuthenticationOrDownstreamCalls() {
        for (String authorization : new String[]{null, "Basic executor-token", "Bearer", "Bearer "}) {
            assertEquals(401, controller.environmentVariables(17L, authorization).getStatusCode().value());
        }

        verifyNoInteractions(authenticator, agentDao, resolver);
    }

    @Test
    void rejectsFailedBearerAuthenticationWithoutDownstreamCalls() {
        stubAuthentication("bad-token", false, 0L, 0L);

        ResponseEntity<?> response = controller.environmentVariables(17L, "Bearer bad-token");

        assertEquals(401, response.getStatusCode().value());
        verify(authenticator).authenticate(17L, "bad-token");
        verifyNoInteractions(agentDao, resolver);
    }

    @Test
    void rejectsMissingAgentWithoutResolvingSnapshot() {
        stubAuthentication("executor-token", true, 51L, 8L);
        when(agentDao.findById(51L)).thenReturn(null);

        ResponseEntity<?> response = controller.environmentVariables(17L, "Bearer executor-token");

        assertEquals(409, response.getStatusCode().value());
        verifyNoInteractions(resolver);
    }

    @Test
    void rejectsCrossTenantAgentWithoutResolvingSnapshot() {
        stubAuthentication("executor-token", true, 51L, 8L);
        when(agentDao.findById(51L)).thenReturn(agent(51L, 9L, 101L));

        ResponseEntity<?> response = controller.environmentVariables(17L, "Bearer executor-token");

        assertEquals(409, response.getStatusCode().value());
        verifyNoInteractions(resolver);
    }

    @Test
    void propagatesSnapshotResolutionFailure() {
        stubAuthentication("executor-token", true, 51L, 8L);
        when(agentDao.findById(51L)).thenReturn(agent(51L, 8L, 101L));
        RuntimeException failure = new RuntimeException("snapshot unavailable");
        when(resolver.resolve(8L, 101L)).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> controller.environmentVariables(17L, "Bearer executor-token"));

        assertEquals(failure, thrown);
        verify(resolver).resolve(8L, 101L);
    }

    private void stubAuthentication(String token, boolean success, long agentId, long tenantId) {
        ExecutorWsAuthenticator.AuthResult result = mock(ExecutorWsAuthenticator.AuthResult.class);
        when(result.isSuccess()).thenReturn(success);
        when(result.getAgentId()).thenReturn(agentId);
        when(result.getTenantId()).thenReturn(tenantId);
        when(authenticator.authenticate(17L, token)).thenReturn(result);
    }

    private static AgentDO agent(long id, long tenantId, Long onlineVersionId) {
        AgentDO agent = new AgentDO();
        agent.setId(id);
        agent.setTenantId(tenantId);
        agent.setOnlineVersionId(onlineVersionId);
        return agent;
    }

    private static void assertEnvironmentVariables(ResponseEntity<?> response,
            Map<String, String> expected) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(expected, body.get("environmentVariables"));
    }
}
