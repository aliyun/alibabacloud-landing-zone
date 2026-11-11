package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SsoAccountProvisioningServiceTest {
    private static final Long PROVIDER_ID = 7L;
    private static final String ISSUER = "https://idp.example.com";
    private static final String SUBJECT = "employee-10482";

    private UserDao userDao;
    private FederatedIdentityDao identityDao;
    private SsoAccountProvisioningService service;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        identityDao = mock(FederatedIdentityDao.class);
        service = new SsoAccountProvisioningService(userDao, identityDao);
        doAnswer(invocation -> {
            UserDO user = invocation.getArgument(0);
            user.setId(555L);
            return 1;
        }).when(userDao).insert(any());
    }

    @Test
    void createsAPasswordlessNormalUserAndBindsTheIdentity() {
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(null);

        FederatedIdentityDO binding = service.provision(identity("林晓", "lin@corp.example.com"));

        ArgumentCaptor<UserDO> created = ArgumentCaptor.forClass(UserDO.class);
        verify(userDao).insert(created.capture());
        assertTrue(created.getValue().getUsername().startsWith("sso_"));
        assertEquals(0, created.getValue().getStatus());
        assertNull(created.getValue().getPasswordHash());
        assertEquals("林晓", created.getValue().getNickname());
        assertEquals("lin@corp.example.com", created.getValue().getEmail());

        ArgumentCaptor<FederatedIdentityDO> saved = ArgumentCaptor.forClass(FederatedIdentityDO.class);
        verify(identityDao).insert(saved.capture());
        assertEquals(555L, saved.getValue().getUserId());
        assertEquals(SUBJECT, saved.getValue().getSubject());
        assertNotNull(saved.getValue().getLastLoginAt());
        assertEquals(555L, binding.getUserId());
    }

    @Test
    void reusesAnExistingBindingWithoutCreatingASecondUser() {
        FederatedIdentityDO existing = new FederatedIdentityDO();
        existing.setUserId(10000L);
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(existing);

        assertSame(existing, service.provision(identity("林晓", null)));
        verify(userDao, never()).insert(any());
    }

    @Test
    void missingDisplayNameFallsBackToTheGeneratedUsername() {
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(null);

        service.provision(identity("   ", ""));

        ArgumentCaptor<UserDO> created = ArgumentCaptor.forClass(UserDO.class);
        verify(userDao).insert(created.capture());
        assertEquals(created.getValue().getUsername(), created.getValue().getNickname());
        assertNull(created.getValue().getEmail());
    }

    @Test
    void retriesWithANewUsernameWhenTheGeneratedOneIsTaken() {
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(null);
        when(userDao.findByUsername(any())).thenReturn(new UserDO());
        doThrow(new DuplicateKeyException("uk_username"))
                .doAnswer(invocation -> {
                    UserDO user = invocation.getArgument(0);
                    user.setId(556L);
                    return 1;
                })
                .when(userDao).insert(any());

        FederatedIdentityDO binding = service.provision(identity("林晓", null));

        assertEquals(556L, binding.getUserId());
        verify(identityDao).insert(any());
    }

    @Test
    void emailCollisionDropsTheEmailAndStillProvisions() {
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(null);
        when(userDao.findByUsername(any())).thenReturn(null);
        doThrow(new DuplicateKeyException("uk_email"))
                .doAnswer(invocation -> {
                    UserDO user = invocation.getArgument(0);
                    user.setId(557L);
                    return 1;
                })
                .when(userDao).insert(any());

        service.provision(identity("林晓", "taken@corp.example.com"));

        ArgumentCaptor<UserDO> created = ArgumentCaptor.forClass(UserDO.class);
        verify(userDao, org.mockito.Mockito.times(2)).insert(created.capture());
        List<UserDO> attempts = created.getAllValues();
        assertEquals("taken@corp.example.com", attempts.get(0).getEmail());
        assertNull(attempts.get(1).getEmail());
    }

    @Test
    void concurrentBindingRaceIsReportedSoTheCallerCanRollBackTheUser() {
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(null);
        doThrow(new DuplicateKeyException("uk_federated_identity_subject"))
                .when(identityDao).insert(any());

        assertThrows(SsoAccountProvisioningService.ProvisionRaceException.class,
                () -> service.provision(identity("林晓", null)));
    }

    @Test
    void exhaustedUsernameAttemptsFailInsteadOfCreatingAPartialAccount() {
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(null);
        when(userDao.findByUsername(any())).thenReturn(new UserDO());
        doThrow(new DuplicateKeyException("uk_username")).when(userDao).insert(any());

        BizException error = assertThrows(BizException.class,
                () -> service.provision(identity("林晓", null)));
        assertEquals("36014", error.getCode());
        verify(identityDao, never()).insert(any());
    }

    private static ExternalIdentity identity(String displayName, String email) {
        return new ExternalIdentity(PROVIDER_ID, ISSUER, SUBJECT, displayName, email, "feishu-1");
    }
}
