package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.workitem.WorkitemDao;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DispatchMcpTokenServiceTest {

    private static final String SECRET =
            "test-secret-test-secret-test-secret-test-secret";
    private static final long DISPATCH_ID = 99L;
    private static final long TENANT_ID = 100L;
    private static final long USER_ID = 7L;

    private DispatchDao dispatchDao;
    private DispatchMcpTokenService service;

    @BeforeEach
    void setUp() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties properties = new JwtProperties(env);
        properties.setSecret(SECRET);
        dispatchDao = mock(DispatchDao.class);
        service = new DispatchMcpTokenService(
                new JwtService(properties), dispatchDao, mock(WorkitemDao.class));
    }

    @Test
    void issuedTokenPreservesDispatchProtocolClaimsAndTwentyFourHourTtl() {
        DispatchDO dispatch = dispatch(DispatchStatus.RUNNING);

        long before = System.currentTimeMillis();
        String token = service.issue(dispatch);
        long after = System.currentTimeMillis();

        assertTrue(token.startsWith("awdispatch_"));
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseClaimsJws(token.substring("awdispatch_".length()))
                .getBody();
        assertEquals("dispatch-mcp", claims.get("purpose", String.class));
        assertEquals(DISPATCH_ID, claims.get("subjectId", Number.class).longValue());
        assertEquals(TENANT_ID, claims.get("org", Number.class).longValue());
        assertEquals(USER_ID, claims.get("uid", Number.class).longValue());
        assertEquals(String.valueOf(USER_ID), claims.getSubject());

        long ttlMillis = Duration.ofHours(24).toMillis();
        assertEquals(ttlMillis,
                claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
        assertTrue(claims.getIssuedAt().getTime() >= before - 1_000L);
        assertTrue(claims.getExpiration().getTime() <= after + ttlMillis);
    }

    @Test
    void authenticatesOnlyEstablishedActiveDispatchStatesAsFixedReadWrite() {
        for (String status : List.of(
                DispatchStatus.PACKAGING,
                DispatchStatus.PENDING,
                DispatchStatus.DISPATCHED,
                DispatchStatus.ACKED,
                DispatchStatus.RUNNING,
                DispatchStatus.PAUSING)) {
            DispatchDO dispatch = dispatch(status);
            when(dispatchDao.findById(DISPATCH_ID)).thenReturn(dispatch);

            McpAccessTokenService.Principal principal =
                    service.authenticate(service.issue(dispatch));

            assertEquals(TENANT_ID, principal.tenantId(), status);
            assertEquals(USER_ID, principal.userId(), status);
            assertEquals(-DISPATCH_ID, principal.tokenId(), status);
            assertEquals(OrgAccessLevel.READ_WRITE, principal.accessLevel(), status);
            assertEquals(McpAccessTokenService.CredentialType.DISPATCH,
                    principal.credentialType(), status);
        }
    }

    @Test
    void rejectsTerminalOrOutOfContractDispatchStates() {
        for (String status : List.of(
                DispatchStatus.SUCCEEDED,
                DispatchStatus.FAILED,
                DispatchStatus.TIMEOUT,
                DispatchStatus.CANCELED,
                DispatchStatus.PAUSED,
                DispatchStatus.PAUSE_FAILED,
                DispatchStatus.WAITING_FOR_PAUSE)) {
            DispatchDO dispatch = dispatch(status);
            when(dispatchDao.findById(DISPATCH_ID)).thenReturn(dispatch);
            String token = service.issue(dispatch);

            assertThrows(BizException.class, () -> service.authenticate(token), status);
        }
    }

    private DispatchDO dispatch(String status) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(DISPATCH_ID);
        dispatch.setTenantId(TENANT_ID);
        dispatch.setCreatorId(USER_ID);
        dispatch.setStatus(status);
        return dispatch;
    }
}
