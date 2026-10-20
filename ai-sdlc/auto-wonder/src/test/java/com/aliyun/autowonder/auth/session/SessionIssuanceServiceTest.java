package com.aliyun.autowonder.auth.session;

import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionIssuanceServiceTest {

    private JwtService jwtService;
    private SessionService sessionService;
    private JwtProperties jwtProperties;
    private SessionIssuanceService service;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        sessionService = mock(SessionService.class);
        jwtProperties = mock(JwtProperties.class);
        when(jwtProperties.getRefreshTtlSeconds()).thenReturn(604800L);
        when(jwtService.signAccess(any())).thenReturn("access-1");
        service = new SessionIssuanceService(jwtService, sessionService, jwtProperties);
    }

    @Test
    void issueSignsAccessTokenWithFreshJtiAndNoWorkspaceBinding() {
        UserDO user = user();

        LoginResponse response = service.issue(user);

        assertEquals(10000L, response.getUserId());
        assertEquals("access-1", response.getAccessToken());
        assertNotNull(response.getRefreshToken());
        assertEquals("alice", response.getUser().getUsername());
        assertEquals("Alice", response.getUser().getNickname());
        assertEquals("alice@corp.example.com", response.getUser().getEmail());
        assertTrue(response.getUser().getIsAdmin());

        ArgumentCaptor<TokenPayload> payload = ArgumentCaptor.forClass(TokenPayload.class);
        verify(jwtService).signAccess(payload.capture());
        assertEquals(10000L, payload.getValue().getUserId());
        assertNull(payload.getValue().getCurrentWorkspaceId());
        assertNotNull(payload.getValue().getJti());

        verify(sessionService).storeRefresh(response.getRefreshToken(), 10000L, 604800);
    }

    @Test
    void everyIssueUsesDistinctJtiAndRefreshToken() {
        LoginResponse first = service.issue(user());
        LoginResponse second = service.issue(user());

        assertNotEquals(first.getRefreshToken(), second.getRefreshToken());
        ArgumentCaptor<TokenPayload> payload = ArgumentCaptor.forClass(TokenPayload.class);
        verify(jwtService, org.mockito.Mockito.times(2)).signAccess(payload.capture());
        assertNotEquals(payload.getAllValues().get(0).getJti(), payload.getAllValues().get(1).getJti());
        verify(sessionService, org.mockito.Mockito.times(2)).storeRefresh(anyString(), anyLong(), anyInt());
    }

    @Test
    void nonAdminUserIsReportedAsNonAdmin() {
        UserDO user = user();
        user.setIsAdmin(0);

        LoginResponse response = service.issue(user);

        assertFalse(response.getUser().getIsAdmin());
    }

    private static UserDO user() {
        UserDO user = new UserDO();
        user.setId(10000L);
        user.setUsername("alice");
        user.setNickname("Alice");
        user.setEmail("alice@corp.example.com");
        user.setIsAdmin(1);
        return user;
    }
}
