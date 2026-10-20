package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.auth.federation.dto.FederatedIdentityVO;
import com.aliyun.autowonder.auth.federation.dto.SsoBindRequest;
import com.aliyun.autowonder.auth.session.SessionIssuanceService;
import com.aliyun.autowonder.common.crypto.PasswordEncoderUtil;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FederatedIdentityServiceTest {
    private static final Long USER = 10000L;
    private static final Long OTHER_USER = 10001L;
    private static final Long PROVIDER_ID = 7L;
    private static final String ISSUER = "https://login.microsoftonline.com/tenant-1/v2.0";
    private static final String SUBJECT = "tenant-1:object-42";

    private FederatedIdentityDao identityDao;
    private FederationConnectionDao connectionDao;
    private FederationRegistryService registryService;
    private UserDao userDao;
    private FederationStateStore stateStore;
    private SessionIssuanceService sessionIssuanceService;
    private FederatedIdentityService service;

    @BeforeEach
    void setUp() {
        identityDao = mock(FederatedIdentityDao.class);
        connectionDao = mock(FederationConnectionDao.class);
        registryService = mock(FederationRegistryService.class);
        userDao = mock(UserDao.class);
        stateStore = mock(FederationStateStore.class);
        sessionIssuanceService = mock(SessionIssuanceService.class);
        service = new FederatedIdentityService(identityDao, connectionDao, registryService, userDao,
                stateStore, sessionIssuanceService);
    }

    @Test
    void listMineDecoratesBindingsWithProviderNameAndProtocol() {
        when(connectionDao.listAll()).thenReturn(List.of(connection(PROVIDER_ID, "Entra 租户 A 登录", "OIDC")));
        when(identityDao.listByUser(USER)).thenReturn(List.of(binding(5L, USER), binding(6L, USER)));

        List<FederatedIdentityVO> identities = service.listMine(USER);

        assertEquals(2, identities.size());
        assertEquals("Entra 租户 A 登录", identities.get(0).getProviderName());
        assertEquals("OIDC", identities.get(0).getProtocol());
        assertEquals(ISSUER, identities.get(0).getIssuer());
        assertEquals(SUBJECT, identities.get(0).getSubject());
    }

    @Test
    void listMineToleratesBindingsOfRemovedConnections() {
        when(connectionDao.listAll()).thenReturn(List.of());
        FederatedIdentityDO orphan = binding(5L, USER);
        orphan.setProviderId(99L);
        when(identityDao.listByUser(USER)).thenReturn(List.of(orphan));

        List<FederatedIdentityVO> identities = service.listMine(USER);

        assertNull(identities.get(0).getProviderName());
        assertNull(identities.get(0).getProtocol());
    }

    @Test
    void findBindingUsesIssuerAndSubjectOfTheVerifiedIdentity() {
        FederatedIdentityDO stored = binding(5L, USER);
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(stored);

        assertSame(stored, service.findBinding(identity()));
        verify(identityDao).findBinding(PROVIDER_ID, ISSUER, SUBJECT);
    }

    @Test
    void createBindingIsIdempotentForTheSameUserAndRejectsForeignOwners() {
        assertBizCode("36012", () -> service.createBinding(identity(), null));

        FederatedIdentityDO existing = binding(5L, USER);
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(existing);
        assertSame(existing, service.createBinding(identity(), USER));
        verify(identityDao, never()).insert(any());

        FederatedIdentityDO foreign = binding(5L, OTHER_USER);
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(foreign);
        assertBizCode("36006", () -> service.createBinding(identity(), USER));
    }

    @Test
    void createBindingInsertsVerifiedIdentityAndNeverMatchesByEmail() {
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(null);

        FederatedIdentityDO created = service.createBinding(identity(), USER);

        ArgumentCaptor<FederatedIdentityDO> inserted = ArgumentCaptor.forClass(FederatedIdentityDO.class);
        verify(identityDao).insert(inserted.capture());
        assertEquals(PROVIDER_ID, inserted.getValue().getProviderId());
        assertEquals(ISSUER, inserted.getValue().getIssuer());
        assertEquals(SUBJECT, inserted.getValue().getSubject());
        assertEquals(USER, inserted.getValue().getUserId());
        assertEquals("Alice Zhang", inserted.getValue().getDisplayName());
        assertEquals("alice@corp.example.com", inserted.getValue().getEmail());
        assertNotNull(inserted.getValue().getLastLoginAt());
        assertSame(created, inserted.getValue());
    }

    @Test
    void createBindingTreatsConcurrentDuplicateAsConflictOrSameOwner() {
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(null);
        doThrow(new DuplicateKeyException("uk_federated_identity_subject")).when(identityDao).insert(any());

        FederatedIdentityDO racedOwn = binding(5L, USER);
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT))
                .thenReturn(null)
                .thenReturn(racedOwn);
        assertSame(racedOwn, service.createBinding(identity(), USER));

        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT))
                .thenReturn(null)
                .thenReturn(binding(5L, OTHER_USER));
        assertBizCode("36006", () -> service.createBinding(identity(), USER));

        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(null);
        assertBizCode("36006", () -> service.createBinding(identity(), USER));
    }

    @Test
    void touchLoginRefreshesDisplayFieldsOfTheBinding() {
        FederatedIdentityDO stored = binding(5L, USER);

        service.touchLogin(stored, identity());

        verify(identityDao).updateLastLogin(5L, "Alice Zhang", "alice@corp.example.com");
    }

    @Test
    void bindWithCredentialsRequiresAValidBindingTicket() {
        assertBizCode("36012", () -> service.bindWithCredentials(null));
        assertBizCode("36012", () -> service.bindWithCredentials(new SsoBindRequest()));

        when(stateStore.readTicket("ticket-1")).thenReturn(null);
        assertBizCode("36012", () -> service.bindWithCredentials(request("ticket-1", "alice", "secret")));

        FederationTicket loginTicket = ticket(FederationTicket.Purpose.LOGIN);
        when(stateStore.readTicket("ticket-1")).thenReturn(loginTicket);
        assertBizCode("36012", () -> service.bindWithCredentials(request("ticket-1", "alice", "secret")));
        verifyNoInteractions(userDao);
    }

    @Test
    void bindWithCredentialsRejectsWrongCredentialsWithoutConsumingTheTicket() {
        stubBindingTicket("ticket-1");

        assertUnauthorized(() -> service.bindWithCredentials(request("ticket-1", "  ", "secret")));

        when(userDao.findByUsername("alice")).thenReturn(null);
        assertUnauthorized(() -> service.bindWithCredentials(request("ticket-1", "alice", "secret")));

        UserDO pureSsoUser = user(USER, null, 0);
        when(userDao.findByUsername("alice")).thenReturn(pureSsoUser);
        assertUnauthorized(() -> service.bindWithCredentials(request("ticket-1", "alice", "secret")));

        UserDO localUser = user(USER, PasswordEncoderUtil.encode("right-secret"), 0);
        when(userDao.findByUsername("alice")).thenReturn(localUser);
        assertUnauthorized(() -> service.bindWithCredentials(request("ticket-1", "alice", "wrong-secret")));
        assertUnauthorized(() -> {
            SsoBindRequest request = request("ticket-1", "alice", null);
            service.bindWithCredentials(request);
        });

        verify(stateStore, never()).deleteTicket(any());
        verify(identityDao, never()).insert(any());
    }

    @Test
    void bindWithCredentialsRejectsDisabledAccounts() {
        stubBindingTicket("ticket-1");
        when(userDao.findByUsername("alice")).thenReturn(user(USER, PasswordEncoderUtil.encode("secret"), 1));

        assertBizCode("36007", () -> service.bindWithCredentials(request("ticket-1", "alice", "secret")));
    }

    @Test
    void bindWithCredentialsBindsTheIdentityConsumesTheTicketAndIssuesASession() {
        stubBindingTicket("ticket-1");
        UserDO user = user(USER, PasswordEncoderUtil.encode("secret"), 0);
        when(userDao.findByUsername("alice")).thenReturn(user);
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(null);
        LoginResponse issued = new LoginResponse(USER, "access-1", "refresh-1", null);
        when(sessionIssuanceService.issue(user)).thenReturn(issued);

        LoginResponse response = service.bindWithCredentials(request("ticket-1", " alice ", "secret"));

        assertSame(issued, response);
        ArgumentCaptor<FederatedIdentityDO> inserted = ArgumentCaptor.forClass(FederatedIdentityDO.class);
        verify(identityDao).insert(inserted.capture());
        assertEquals(USER, inserted.getValue().getUserId());
        verify(stateStore).deleteTicket("ticket-1");
        verify(sessionIssuanceService).issue(user);
    }

    @Test
    void claimRequiresTicketAndActiveUser() {
        when(stateStore.readTicket("ticket-1")).thenReturn(null);
        assertBizCode("36012", () -> service.claim(USER, "ticket-1"));

        stubBindingTicket("ticket-1");
        when(userDao.findById(USER)).thenReturn(null);
        assertBizCode("36007", () -> service.claim(USER, "ticket-1"));

        when(userDao.findById(USER)).thenReturn(user(USER, null, 1));
        assertBizCode("36007", () -> service.claim(USER, "ticket-1"));
    }

    @Test
    void claimBindsToTheLoggedInUserAndReturnsTheProviderView() {
        stubBindingTicket("ticket-1");
        when(userDao.findById(USER)).thenReturn(user(USER, null, 0));
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(null);
        when(connectionDao.findById(PROVIDER_ID))
                .thenReturn(connection(PROVIDER_ID, "Entra 租户 A 登录", "OIDC"));

        FederatedIdentityVO claimed = service.claim(USER, "ticket-1");

        assertEquals(USER, capturedInsertedUserId());
        assertEquals("Entra 租户 A 登录", claimed.getProviderName());
        assertEquals("OIDC", claimed.getProtocol());
        verify(stateStore).deleteTicket("ticket-1");
    }

    @Test
    void claimBlocksIdentityThatBelongsToAnotherAccount() {
        stubBindingTicket("ticket-1");
        when(userDao.findById(USER)).thenReturn(user(USER, null, 0));
        when(identityDao.findBinding(PROVIDER_ID, ISSUER, SUBJECT)).thenReturn(binding(5L, OTHER_USER));

        assertBizCode("36006", () -> service.claim(USER, "ticket-1"));
        verify(stateStore, never()).deleteTicket(any());
    }

    @Test
    void claimRejectsBindingTicketsOfProvidersDisabledAfterTheTicketWasIssued() {
        stubBindingTicket("ticket-1");
        when(registryService.requireEnabledConnectionState(PROVIDER_ID))
                .thenThrow(new BizException(ErrorCode.FEDERATION_CONNECTION_NOT_FOUND));

        assertBizCode("36001", () -> service.claim(USER, "ticket-1"));
        verify(registryService).requireEnabledConnectionState(PROVIDER_ID);
        verify(userDao, never()).findById(any());
        verify(identityDao, never()).insert(any());
        verify(stateStore, never()).deleteTicket(any());
    }

    @Test
    void bindWithCredentialsChecksTheProviderBeforeVerifyingCredentials() {
        stubBindingTicket("ticket-1");
        when(registryService.requireEnabledConnectionState(PROVIDER_ID))
                .thenThrow(new BizException(ErrorCode.FEDERATION_CONNECTION_NOT_FOUND));

        assertBizCode("36001", () -> service.bindWithCredentials(request("ticket-1", "alice", "secret")));
        verifyNoInteractions(userDao);
        verify(stateStore, never()).deleteTicket(any());
    }

    @Test
    void unbindRejectsUnknownOrForeignBindings() {
        assertBizCode("10404", () -> service.unbind(USER, null));

        when(identityDao.findById(5L)).thenReturn(null);
        assertBizCode("10404", () -> service.unbind(USER, 5L));

        when(identityDao.findById(5L)).thenReturn(binding(5L, OTHER_USER));
        assertBizCode("10404", () -> service.unbind(USER, 5L));
        verify(identityDao, never()).deleteById(anyLong());
    }

    @Test
    void unbindKeepsTheLastLoginMethodForPureSsoAccounts() {
        when(identityDao.findById(5L)).thenReturn(binding(5L, USER));
        when(userDao.findById(USER)).thenReturn(user(USER, null, 0));
        when(identityDao.countByUser(USER)).thenReturn(1L);

        assertBizCode("36008", () -> service.unbind(USER, 5L));
        verify(identityDao, never()).deleteById(anyLong());

        when(userDao.findById(USER)).thenReturn(user(USER, "   ", 0));
        assertBizCode("36008", () -> service.unbind(USER, 5L));

        when(userDao.findById(USER)).thenReturn(null);
        assertBizCode("36008", () -> service.unbind(USER, 5L));
    }

    @Test
    void unbindAllowsRemovalWhenALocalPasswordOrAnotherIdentityRemains() {
        when(identityDao.findById(5L)).thenReturn(binding(5L, USER));
        when(userDao.findById(USER)).thenReturn(user(USER, PasswordEncoderUtil.encode("secret"), 0));

        service.unbind(USER, 5L);
        verify(identityDao).deleteById(5L);

        when(userDao.findById(USER)).thenReturn(user(USER, null, 0));
        when(identityDao.countByUser(USER)).thenReturn(2L);
        service.unbind(USER, 5L);
        verify(identityDao, org.mockito.Mockito.times(2)).deleteById(5L);
    }

    private void stubBindingTicket(String value) {
        when(stateStore.readTicket(value)).thenReturn(ticket(FederationTicket.Purpose.BINDING));
    }

    private Long capturedInsertedUserId() {
        ArgumentCaptor<FederatedIdentityDO> inserted = ArgumentCaptor.forClass(FederatedIdentityDO.class);
        verify(identityDao).insert(inserted.capture());
        return inserted.getValue().getUserId();
    }

    private static SsoBindRequest request(String ticket, String username, String password) {
        SsoBindRequest request = new SsoBindRequest();
        request.setTicket(ticket);
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    private static FederationTicket ticket(FederationTicket.Purpose purpose) {
        FederationTicket ticket = new FederationTicket();
        ticket.setPurpose(purpose);
        ticket.setProviderId(PROVIDER_ID);
        ticket.setIssuer(ISSUER);
        ticket.setSubject(SUBJECT);
        ticket.setDisplayName("Alice Zhang");
        ticket.setEmail("alice@corp.example.com");
        ticket.setUserId(purpose == FederationTicket.Purpose.LOGIN ? USER : null);
        return ticket;
    }

    private static ExternalIdentity identity() {
        return new ExternalIdentity(PROVIDER_ID, ISSUER, SUBJECT, "Alice Zhang", "alice@corp.example.com");
    }

    private static FederatedIdentityDO binding(Long id, Long userId) {
        FederatedIdentityDO binding = new FederatedIdentityDO();
        binding.setId(id);
        binding.setProviderId(PROVIDER_ID);
        binding.setIssuer(ISSUER);
        binding.setSubject(SUBJECT);
        binding.setUserId(userId);
        binding.setDisplayName("Alice Zhang");
        binding.setEmail("alice@corp.example.com");
        return binding;
    }

    private static FederationConnectionDO connection(Long id, String name, String protocol) {
        FederationConnectionDO connection = new FederationConnectionDO();
        connection.setId(id);
        connection.setName(name);
        connection.setProtocol(protocol);
        return connection;
    }

    private static UserDO user(Long id, String passwordHash, int status) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setUsername("alice");
        user.setPasswordHash(passwordHash);
        user.setStatus(status);
        return user;
    }

    private static void assertUnauthorized(ThrowingSupplier action) {
        BizException failure = assertBizCode("10401", action);
        assertEquals("user.credentials_incorrect", failure.getMessageKey());
    }

    private static BizException assertBizCode(String code, ThrowingSupplier action) {
        BizException failure = assertThrowsBizException(action);
        assertEquals(code, failure.getCode());
        return failure;
    }

    private static BizException assertThrowsBizException(ThrowingSupplier action) {
        try {
            action.get();
        } catch (BizException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("expected BizException but got " + e, e);
        }
        throw new AssertionError("expected BizException but nothing was thrown");
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        void get() throws Exception;
    }
}
