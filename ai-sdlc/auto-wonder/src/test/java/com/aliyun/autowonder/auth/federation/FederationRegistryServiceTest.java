package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.auth.federation.dto.CreateConnectionRequest;
import com.aliyun.autowonder.auth.federation.dto.CreateIdpRequest;
import com.aliyun.autowonder.auth.federation.dto.FederationConnectionVO;
import com.aliyun.autowonder.auth.federation.dto.FederationIdpVO;
import com.aliyun.autowonder.auth.federation.dto.FederationOverviewVO;
import com.aliyun.autowonder.auth.federation.dto.SsoProviderVO;
import com.aliyun.autowonder.auth.federation.dto.UpdateConnectionRequest;
import com.aliyun.autowonder.auth.federation.dto.UpdateIdpRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.security.crypto.AesGcmSecretCrypto;
import com.aliyun.autowonder.security.crypto.SecretCryptoProperties;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FederationRegistryServiceTest {
    private static final Long IDP_ID = 3L;
    private static final Long PROVIDER_ID = 7L;

    private FederationIdpDao idpDao;
    private FederationConnectionDao connectionDao;
    private FederatedIdentityDao identityDao;
    private SecretCrypto secretCrypto;
    private FederationProtocolAdapter samlAdapter;
    private FederationProtocolAdapter oidcAdapter;
    private FederationRegistryService service;

    @BeforeEach
    void setUp() {
        idpDao = mock(FederationIdpDao.class);
        connectionDao = mock(FederationConnectionDao.class);
        identityDao = mock(FederatedIdentityDao.class);
        secretCrypto = mock(SecretCrypto.class);
        samlAdapter = mock(FederationProtocolAdapter.class);
        oidcAdapter = mock(FederationProtocolAdapter.class);
        when(samlAdapter.protocol()).thenReturn(FederationProtocol.SAML2);
        when(oidcAdapter.protocol()).thenReturn(FederationProtocol.OIDC);
        when(secretCrypto.decrypt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(secretCrypto.encrypt(any())).thenAnswer(invocation -> {
            String plain = invocation.getArgument(0);
            return plain == null ? null : "kc:v1:cipher";
        });
        when(secretCrypto.mask(anyString())).thenReturn("********");
        service = new FederationRegistryService(idpDao, connectionDao, identityDao, secretCrypto,
                List.of(samlAdapter, oidcAdapter));
    }

    @Test
    void adapterForReturnsTheAdapterOfTheProtocolAndFailsClosedOtherwise() {
        assertSame(samlAdapter, service.adapterFor(FederationProtocol.SAML2));
        assertSame(oidcAdapter, service.adapterFor(FederationProtocol.OIDC));

        assertConfigInvalid(() -> service.adapterFor(null));
        assertConfigInvalid(() -> service.adapterFor(FederationProtocol.parse("nonsense")));
    }

    @Test
    void loginProvidersOnlyExposeEnabledConnectionsOfEnabledIdps() {
        when(idpDao.listAll()).thenReturn(List.of(idp(IDP_ID, "AD FS 生产", 1), idp(4L, "Entra 已停用", 0)));
        FederationConnectionDO enabled = connection(PROVIDER_ID, IDP_ID, "AD FS 生产", "SAML2");
        FederationConnectionDO orphan = connection(8L, 99L, "孤儿连接", "SAML2");
        FederationConnectionDO underDisabledIdp = connection(9L, 4L, "Entra 登录", "OIDC");
        when(connectionDao.listByEnabled(1)).thenReturn(List.of(enabled, orphan, underDisabledIdp));

        List<SsoProviderVO> providers = service.listLoginProviders();

        assertEquals(1, providers.size());
        assertEquals(PROVIDER_ID, providers.get(0).getProviderId());
        assertEquals("AD FS 生产", providers.get(0).getName());
        assertEquals("SAML2", providers.get(0).getProtocol());
        assertEquals("AD FS 生产", providers.get(0).getIdpName());
    }

    @Test
    void enabledConnectionRequiresBothConnectionAndIdpEnabled() {
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(connection(PROVIDER_ID, IDP_ID, "AD FS", "SAML2"));
        when(idpDao.findById(IDP_ID)).thenReturn(idp(IDP_ID, "AD FS 生产", 1));

        assertEquals(PROVIDER_ID, service.requireEnabledConnection(PROVIDER_ID).getId());

        FederationConnectionDO disabled = connection(PROVIDER_ID, IDP_ID, "AD FS", "SAML2");
        disabled.setEnabled(0);
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(disabled);
        assertConnectionNotFound(() -> service.requireEnabledConnection(PROVIDER_ID));

        FederationConnectionDO enabled = connection(PROVIDER_ID, IDP_ID, "AD FS", "SAML2");
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(enabled);
        when(idpDao.findById(IDP_ID)).thenReturn(idp(IDP_ID, "AD FS 生产", 0));
        assertConnectionNotFound(() -> service.requireEnabledConnection(PROVIDER_ID));

        when(idpDao.findById(IDP_ID)).thenReturn(null);
        assertConnectionNotFound(() -> service.requireEnabledConnection(PROVIDER_ID));
    }

    @Test
    void requireConnectionRejectsBlankIdAndMissingRowAndDecryptsSecret() {
        assertParamInvalidKey("federation.provider_required", () -> service.requireConnection(null));

        when(connectionDao.findById(PROVIDER_ID)).thenReturn(null);
        assertConnectionNotFound(() -> service.requireConnection(PROVIDER_ID));

        FederationConnectionDO stored = connection(PROVIDER_ID, IDP_ID, "Entra 登录", "OIDC");
        stored.setClientSecret("kc:v1:cipher");
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(stored);
        when(secretCrypto.decrypt("kc:v1:cipher")).thenReturn("plain-secret");

        assertEquals("plain-secret", service.requireConnection(PROVIDER_ID).getClientSecret());
    }

    @Test
    void idpCrudValidatesNameAndGuardsDeletionWhileConnectionsExist() {
        AtomicReference<FederationIdpDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            FederationIdpDO row = invocation.getArgument(0);
            row.setId(11L);
            inserted.set(row);
            return 1;
        }).when(idpDao).insert(any());
        when(idpDao.findById(11L)).thenAnswer(invocation -> inserted.get());
        when(idpDao.listAll()).thenAnswer(invocation -> List.of(inserted.get()));

        CreateIdpRequest create = new CreateIdpRequest();
        create.setName("  Entra 租户 A  ");
        create.setDescription("总部");
        FederationIdpVO created = service.createIdp(create, 10000L);
        assertEquals(11L, created.getId());
        assertEquals("Entra 租户 A", created.getName());
        assertEquals(1, created.getEnabled());
        assertEquals(10000L, created.getCreatedBy());

        assertParamInvalidKey("federation.idp_name_required", () -> service.createIdp(null, 10000L));
        CreateIdpRequest blank = new CreateIdpRequest();
        blank.setName("   ");
        assertParamInvalidKey("federation.idp_name_required", () -> service.createIdp(blank, 10000L));
        CreateIdpRequest tooLong = new CreateIdpRequest();
        tooLong.setName("x".repeat(65));
        BizException failure = assertParamInvalid(() -> service.createIdp(tooLong, 10000L));
        assertEquals("federation.name_too_long", failure.getMessageKey());
        assertArrayEquals(new Object[]{64}, failure.getMessageArgs());

        UpdateIdpRequest update = new UpdateIdpRequest();
        update.setName("Entra 租户 B");
        update.setDescription("灾备");
        update.setEnabled(0);
        FederationIdpVO updated = service.updateIdp(11L, update);
        assertEquals("Entra 租户 B", updated.getName());
        assertEquals("灾备", updated.getDescription());
        assertEquals(0, updated.getEnabled());
        assertEquals(11L, service.listIdps().get(0).getId());

        UpdateIdpRequest untouched = new UpdateIdpRequest();
        assertEquals("Entra 租户 B", service.updateIdp(11L, untouched).getName());

        UpdateIdpRequest enableOnly = new UpdateIdpRequest();
        enableOnly.setEnabled(1);
        assertEquals(1, service.updateIdp(11L, enableOnly).getEnabled());

        assertIdpNotFound(() -> service.updateIdp(99L, update));
        assertIdpNotFound(() -> service.deleteIdp(99L));

        when(connectionDao.countByIdp(11L)).thenReturn(2L);
        assertBizCode("36010", () -> service.deleteIdp(11L));
        verify(idpDao, never()).deleteById(11L);

        when(connectionDao.countByIdp(11L)).thenReturn(0L);
        service.deleteIdp(11L);
        verify(idpDao).deleteById(11L);
    }

    @Test
    void connectionCrudValidatesProtocolFieldsNameUniquenessAndSecretHandling() {
        stubIdp();
        AtomicReference<FederationConnectionDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            FederationConnectionDO row = invocation.getArgument(0);
            row.setId(PROVIDER_ID);
            inserted.set(row);
            return 1;
        }).when(connectionDao).insert(any());
        when(connectionDao.findById(PROVIDER_ID)).thenAnswer(invocation -> inserted.get());

        FederationConnectionVO created = service.createConnection(oidcRequest("Entra 租户 A 登录"));

        assertEquals(PROVIDER_ID, created.getId());
        assertEquals(IDP_ID, created.getIdpId());
        assertEquals("OIDC", created.getProtocol());
        assertEquals(1, created.getEnabled());
        assertEquals("kc:v1:cipher", inserted.get().getClientSecret());
        assertTrue(created.getClientSecretConfigured());
        assertEquals("********", created.getClientSecretMasked());

        assertParamInvalidKey("federation.provider_required", () -> service.createConnection(null));

        CreateConnectionRequest unknownIdp = oidcRequest("未知 IdP 的连接");
        unknownIdp.setIdpId(99L);
        assertIdpNotFound(() -> service.createConnection(unknownIdp));

        CreateConnectionRequest badProtocol = oidcRequest("协议不合法");
        badProtocol.setProtocol("LDAP");
        assertParamInvalidKey("federation.protocol_invalid", () -> service.createConnection(badProtocol));

        when(connectionDao.findByName("Entra 租户 A 登录"))
                .thenReturn(connection(50L, IDP_ID, "Entra 租户 A 登录", "OIDC"));
        assertBizCode("36011", () -> service.createConnection(oidcRequest("Entra 租户 A 登录")));

        when(connectionDao.findByName(anyString())).thenReturn(null);
        CreateConnectionRequest withoutIssuer = oidcRequest("缺少 Issuer");
        withoutIssuer.setIssuer("  ");
        assertParamInvalidKey("federation.issuer_required", () -> service.createConnection(withoutIssuer));

        CreateConnectionRequest samlWithoutCert = oidcRequest("SAML 缺配置");
        samlWithoutCert.setProtocol("SAML2");
        samlWithoutCert.setClientSecret(null);
        assertParamInvalidKey("federation.saml_config_required", () -> service.createConnection(samlWithoutCert));

        CreateConnectionRequest oidcWithoutEndpoint = oidcRequest("OIDC 缺端点");
        oidcWithoutEndpoint.setOidcJwksUrl("  ");
        assertParamInvalidKey("federation.oidc_config_required", () -> service.createConnection(oidcWithoutEndpoint));

        CreateConnectionRequest oidcWithoutSecret = oidcRequest("OIDC 缺密钥");
        oidcWithoutSecret.setClientSecret(null);
        assertParamInvalidKey("federation.client_secret_required", () -> service.createConnection(oidcWithoutSecret));
    }

    @Test
    void samlConnectionCanBeCreatedWithoutClientSecretAndStoresNull() {
        useRealSecretCrypto();
        stubIdp();
        AtomicReference<FederationConnectionDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            FederationConnectionDO row = invocation.getArgument(0);
            row.setId(PROVIDER_ID);
            inserted.set(row);
            return 1;
        }).when(connectionDao).insert(any());
        when(connectionDao.findById(PROVIDER_ID)).thenAnswer(invocation -> inserted.get());

        CreateConnectionRequest create = new CreateConnectionRequest();
        create.setIdpId(IDP_ID);
        create.setName("AD FS 生产");
        create.setProtocol("saml2");
        create.setIssuer("https://adfs.corp.example.com/adfs/services/trust");
        create.setSamlSsoUrl("https://adfs.corp.example.com/adfs/ls");
        create.setSamlCert("cert-1");

        FederationConnectionVO created = service.createConnection(create);

        assertEquals("SAML2", created.getProtocol());
        assertNull(inserted.get().getClientSecret());
        assertFalse(created.getClientSecretConfigured());
        assertNull(created.getClientSecretMasked());
    }

    @Test
    void samlConnectionCanBeReadWithoutClientSecretUsingRealCrypto() {
        useRealSecretCrypto();
        stubIdp();
        when(connectionDao.findById(PROVIDER_ID))
                .thenAnswer(invocation -> connection(PROVIDER_ID, IDP_ID, "SAML", "SAML2"));

        assertNull(service.requireConnection(PROVIDER_ID).getClientSecret());
        assertNull(service.requireEnabledConnection(PROVIDER_ID).getClientSecret());
    }

    @Test
    void oidcSecretRemainsEncryptedOnBlankUpdateAndDecryptsUsingRealCrypto() {
        useRealSecretCrypto();
        stubIdp();
        AtomicReference<FederationConnectionDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            FederationConnectionDO row = invocation.getArgument(0);
            row.setId(PROVIDER_ID);
            inserted.set(row);
            return 1;
        }).when(connectionDao).insert(any());
        when(connectionDao.findById(PROVIDER_ID)).thenAnswer(invocation -> inserted.get());
        FederationConnectionVO created = service.createConnection(oidcRequest("OIDC"));
        String encrypted = inserted.get().getClientSecret();
        assertTrue(encrypted.startsWith("enc:v1:"));
        assertTrue(created.getClientSecretConfigured());
        assertFalse(created.getClientSecretMasked().contains("plain-secret"));
        UpdateConnectionRequest update = new UpdateConnectionRequest();
        update.setClientSecret("  ");
        service.updateConnection(PROVIDER_ID, update);
        assertEquals(encrypted, inserted.get().getClientSecret());
        assertEquals("plain-secret", service.requireConnection(PROVIDER_ID).getClientSecret());
        inserted.get().setClientSecret(encrypted);
        assertEquals("plain-secret", service.requireEnabledConnection(PROVIDER_ID).getClientSecret());
    }

    private void useRealSecretCrypto() {
        SecretCryptoProperties properties = new SecretCryptoProperties();
        properties.setMasterKey(Base64.getEncoder().encodeToString(new byte[32]));
        secretCrypto = new AesGcmSecretCrypto(properties);
        service = new FederationRegistryService(idpDao, connectionDao, identityDao, secretCrypto,
                List.of(samlAdapter, oidcAdapter));
    }

    @Test
    void connectionListingResolvesIdpNameAndMasksSecret() {
        when(idpDao.listAll()).thenReturn(List.of(idp(IDP_ID, "AD FS 生产", 1)));
        FederationConnectionDO withSecret = connection(PROVIDER_ID, IDP_ID, "AD FS 生产", "SAML2");
        withSecret.setClientSecret("kc:v1:cipher");
        FederationConnectionDO withoutSecret = connection(8L, 99L, "孤儿连接", "OIDC");
        when(connectionDao.listAll()).thenReturn(List.of(withSecret, withoutSecret));

        List<FederationConnectionVO> connections = service.listConnections();

        assertEquals(2, connections.size());
        assertEquals("AD FS 生产", connections.get(0).getIdpName());
        assertEquals("********", connections.get(0).getClientSecretMasked());
        assertNull(connections.get(1).getIdpName());
        assertFalse(connections.get(1).getClientSecretConfigured());
    }

    @Test
    void connectionUpdateKeepsNullsClearsBlanksAndReEncryptsSecret() {
        stubIdp();
        AtomicReference<FederationConnectionDO> stored = new AtomicReference<>(
                connection(PROVIDER_ID, IDP_ID, "Entra 租户 A 登录", "OIDC"));
        stored.get().setClientSecret("kc:v1:cipher");
        stored.get().setScopes("openid");
        when(connectionDao.findById(PROVIDER_ID)).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(connectionDao).update(any());

        UpdateConnectionRequest update = new UpdateConnectionRequest();
        update.setName("Entra 租户 A 登录 v2");
        update.setEnabled(0);
        update.setScopes("   ");
        update.setSortOrder(4);
        update.setClientSecret("  new-secret  ");

        FederationConnectionVO updated = service.updateConnection(PROVIDER_ID, update);

        assertEquals("Entra 租户 A 登录 v2", updated.getName());
        assertEquals(0, updated.getEnabled());
        assertEquals(4, updated.getSortOrder());
        assertEquals("https://issuer.example.com", stored.get().getIssuer());
        assertNull(stored.get().getScopes());
        assertEquals("kc:v1:cipher", stored.get().getClientSecret());
        verify(secretCrypto).encrypt("new-secret");
        verify(connectionDao).findByName("Entra 租户 A 登录 v2");
    }

    @Test
    void connectionUpdateWithoutBodyAndWithForeignNameBehavesSafely() {
        stubIdp();
        FederationConnectionDO stored = connection(PROVIDER_ID, IDP_ID, "Entra 租户 A 登录", "OIDC");
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(stored);

        assertEquals("Entra 租户 A 登录", service.updateConnection(PROVIDER_ID, null).getName());
        verify(connectionDao, never()).update(any());

        when(connectionDao.findByName("别人的登录方式"))
                .thenReturn(connection(50L, IDP_ID, "别人的登录方式", "OIDC"));
        UpdateConnectionRequest rename = new UpdateConnectionRequest();
        rename.setName("别人的登录方式");
        assertBizCode("36011", () -> service.updateConnection(PROVIDER_ID, rename));

        assertConnectionNotFound(() -> service.updateConnection(99L, rename));
    }

    @Test
    void connectionUpdateAcceptsItsOwnNameAndRejectsClearingRequiredIssuer() {
        stubIdp();
        FederationConnectionDO stored = connection(PROVIDER_ID, IDP_ID, "Entra 租户 A 登录", "OIDC");
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(stored);
        when(connectionDao.findByName("Entra 租户 A 登录")).thenReturn(stored);

        UpdateConnectionRequest sameName = new UpdateConnectionRequest();
        sameName.setName("Entra 租户 A 登录");
        assertEquals("Entra 租户 A 登录", service.updateConnection(PROVIDER_ID, sameName).getName());

        UpdateConnectionRequest clearIssuer = new UpdateConnectionRequest();
        clearIssuer.setIssuer("   ");
        assertParamInvalidKey("federation.issuer_required", () -> service.updateConnection(PROVIDER_ID, clearIssuer));
    }

    @Test
    void connectionUpdateNeverDecryptsOrRewritesStoredCiphertextWithoutANewSecret() {
        stubIdp();
        AtomicReference<FederationConnectionDO> stored = new AtomicReference<>(
                connection(PROVIDER_ID, IDP_ID, "Entra 租户 A 登录", "OIDC"));
        stored.get().setClientSecret("kc:v1:cipher");
        when(connectionDao.findById(PROVIDER_ID)).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(connectionDao).update(any());

        UpdateConnectionRequest nullSecret = new UpdateConnectionRequest();
        nullSecret.setName("Entra 租户 A 登录 v2");
        service.updateConnection(PROVIDER_ID, nullSecret);
        assertEquals("kc:v1:cipher", stored.get().getClientSecret());

        UpdateConnectionRequest blankSecret = new UpdateConnectionRequest();
        blankSecret.setEnabled(0);
        blankSecret.setClientSecret("   ");
        FederationConnectionVO updated = service.updateConnection(PROVIDER_ID, blankSecret);
        assertEquals("kc:v1:cipher", stored.get().getClientSecret());
        assertTrue(updated.getClientSecretConfigured());

        verify(secretCrypto, never()).decrypt(any());
        verify(secretCrypto, never()).encrypt(any());
    }

    @Test
    void enabledStateCheckAndDeletionNeverDecryptTheSecret() {
        FederationConnectionDO stored = connection(PROVIDER_ID, IDP_ID, "Entra 租户 A 登录", "OIDC");
        stored.setClientSecret("kc:v1:cipher");
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(stored);
        when(idpDao.findById(IDP_ID)).thenReturn(idp(IDP_ID, "Entra 租户 A", 1));

        assertSame(stored, service.requireEnabledConnectionState(PROVIDER_ID));
        service.deleteConnection(PROVIDER_ID);

        verify(secretCrypto, never()).decrypt(any());
        verify(connectionDao).deleteById(PROVIDER_ID);
    }

    @Test
    void enabledStateCheckRejectsDisabledConnectionWithoutDecrypting() {
        FederationConnectionDO disabled = connection(PROVIDER_ID, IDP_ID, "AD FS", "SAML2");
        disabled.setEnabled(0);
        disabled.setClientSecret("kc:v1:cipher");
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(disabled);

        assertConnectionNotFound(() -> service.requireEnabledConnectionState(PROVIDER_ID));
        verify(secretCrypto, never()).decrypt(any());
    }

    @Test
    void overviewProjectsOnlyWhitelistedReadOnlyFields() {
        when(idpDao.listAll()).thenReturn(List.of(idp(IDP_ID, "Entra 租户 A", 1)));
        FederationConnectionDO withSecret = connection(PROVIDER_ID, IDP_ID, "Entra 租户 A 登录", "OIDC");
        withSecret.setClientSecret("kc:v1:cipher");
        FederationConnectionDO orphan = connection(8L, 99L, "孤儿连接", "SAML2");
        when(connectionDao.listAll()).thenReturn(List.of(withSecret, orphan));

        FederationOverviewVO overview = service.overview();

        assertEquals(1, overview.getIdps().size());
        assertEquals(IDP_ID, overview.getIdps().get(0).getId());
        assertEquals("Entra 租户 A", overview.getIdps().get(0).getName());
        assertEquals(1, overview.getIdps().get(0).getEnabled());
        assertEquals(2, overview.getConnections().size());
        FederationOverviewVO.Connection first = overview.getConnections().get(0);
        assertEquals(PROVIDER_ID, first.getId());
        assertEquals(IDP_ID, first.getIdpId());
        assertEquals("Entra 租户 A", first.getIdpName());
        assertEquals("Entra 租户 A 登录", first.getName());
        assertEquals("OIDC", first.getProtocol());
        assertEquals(1, first.getEnabled());
        assertTrue(first.getClientSecretConfigured());
        assertNull(overview.getConnections().get(1).getIdpName());
        assertFalse(overview.getConnections().get(1).getClientSecretConfigured());
        assertEquals(Set.of("id", "idpId", "idpName", "name", "protocol", "enabled",
                        "clientSecretConfigured"),
                fieldNames(FederationOverviewVO.Connection.class));
        assertEquals(Set.of("id", "name", "enabled"), fieldNames(FederationOverviewVO.Idp.class));
    }

    @Test
    void connectionDeleteIsBlockedWhileIdentitiesRemainBound() {
        FederationConnectionDO stored = connection(PROVIDER_ID, IDP_ID, "Entra 租户 A 登录", "OIDC");
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(stored);
        when(identityDao.countByProvider(PROVIDER_ID)).thenReturn(1L);

        assertBizCode("36013", () -> service.deleteConnection(PROVIDER_ID));
        verify(connectionDao, never()).deleteById(anyLong());

        when(identityDao.countByProvider(PROVIDER_ID)).thenReturn(0L);
        service.deleteConnection(PROVIDER_ID);
        verify(connectionDao).deleteById(PROVIDER_ID);
    }

    @Test
    void createConnectionTrimsBlankOptionalFieldsToNull() {
        stubIdp();
        AtomicReference<FederationConnectionDO> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            FederationConnectionDO row = invocation.getArgument(0);
            row.setId(PROVIDER_ID);
            inserted.set(row);
            return 1;
        }).when(connectionDao).insert(any());
        when(connectionDao.findById(PROVIDER_ID)).thenAnswer(invocation -> inserted.get());

        CreateConnectionRequest create = oidcRequest("Entra 租户 A 登录");
        create.setSubjectClaim("  ");
        create.setSortOrder(null);
        create.setEnabled(null);

        service.createConnection(create);

        assertNull(inserted.get().getSubjectClaim());
        assertEquals(0, inserted.get().getSortOrder());
        assertEquals(1, inserted.get().getEnabled());
    }

    private void stubIdp() {
        when(idpDao.findById(IDP_ID)).thenReturn(idp(IDP_ID, "Entra 租户 A", 1));
    }

    private static Set<String> fieldNames(Class<?> type) {
        Set<String> names = new HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                names.add(field.getName());
            }
        }
        return names;
    }

    private static CreateConnectionRequest oidcRequest(String name) {
        CreateConnectionRequest request = new CreateConnectionRequest();
        request.setIdpId(IDP_ID);
        request.setName(name);
        request.setProtocol("OIDC");
        request.setIssuer("https://login.microsoftonline.com/t/v2.0");
        request.setOidcAuthorizeUrl("https://login.microsoftonline.com/t/v2.0/authorize");
        request.setOidcTokenUrl("https://login.microsoftonline.com/t/oauth2/v2.0/token");
        request.setOidcJwksUrl("https://login.microsoftonline.com/t/discovery/v2.0/keys");
        request.setClientId("client-1");
        request.setClientSecret("plain-secret");
        return request;
    }

    private static FederationIdpDO idp(Long id, String name, int enabled) {
        FederationIdpDO idp = new FederationIdpDO();
        idp.setId(id);
        idp.setName(name);
        idp.setEnabled(enabled);
        return idp;
    }

    private static FederationConnectionDO connection(Long id, Long idpId, String name, String protocol) {
        FederationConnectionDO connection = new FederationConnectionDO();
        connection.setId(id);
        connection.setIdpId(idpId);
        connection.setName(name);
        connection.setProtocol(protocol);
        connection.setEnabled(1);
        connection.setIssuer("https://issuer.example.com");
        connection.setOidcAuthorizeUrl("https://issuer.example.com/authorize");
        connection.setOidcTokenUrl("https://issuer.example.com/token");
        connection.setOidcJwksUrl("https://issuer.example.com/keys");
        connection.setClientId("client-1");
        connection.setSamlSsoUrl("https://issuer.example.com/sso");
        connection.setSamlCert("cert-1");
        connection.setSortOrder(0);
        return connection;
    }

    private static void assertConnectionNotFound(ThrowingSupplier action) {
        assertBizCode("36001", action);
    }

    private static void assertIdpNotFound(ThrowingSupplier action) {
        assertBizCode("36009", action);
    }

    private static BizException assertConfigInvalid(ThrowingSupplier action) {
        return assertBizCode("36002", action);
    }

    private static BizException assertParamInvalid(ThrowingSupplier action) {
        return assertBizCode("10001", action);
    }

    private static void assertParamInvalidKey(String messageKey, ThrowingSupplier action) {
        assertEquals(messageKey, assertParamInvalid(action).getMessageKey());
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
