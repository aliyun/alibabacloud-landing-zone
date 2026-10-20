package com.aliyun.autowonder.auth.federation;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederationDaoTest {
    private static final String JDBC_URL = "jdbc:h2:mem:federation_test;MODE=MySQL;DB_CLOSE_DELAY=-1";
    private static final Long USER = 10000L;
    private static final Long OTHER_USER = 10001L;

    private static FederationIdpDao idpDao;
    private static FederationConnectionDao connectionDao;
    private static FederatedIdentityDao identityDao;

    @BeforeAll
    static void init() throws Exception {
        try (InputStream input = Resources.getResourceAsStream("mybatis-federation-test-config.xml")) {
            SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(input));
            idpDao = template.getMapper(FederationIdpDao.class);
            connectionDao = template.getMapper(FederationConnectionDao.class);
            identityDao = template.getMapper(FederatedIdentityDao.class);
        }
        executeScript("federation-schema-h2.sql");
    }

    @BeforeEach
    void clean() throws Exception {
        execute("DELETE FROM federated_identity", "DELETE FROM federation_connection", "DELETE FROM federation_idp");
    }

    @Test
    void idpInsertGeneratesIdAndUpdatePersistsEditableFields() {
        FederationIdpDO idp = new FederationIdpDO();
        idp.setName("AD FS 生产");
        idp.setDescription("总部域控");
        idp.setEnabled(1);
        idp.setCreatedBy(USER);

        assertEquals(1, idpDao.insert(idp));
        assertNotNull(idp.getId());

        FederationIdpDO stored = idpDao.findById(idp.getId());
        assertEquals("AD FS 生产", stored.getName());
        assertEquals("总部域控", stored.getDescription());
        assertEquals(1, stored.getEnabled());
        assertEquals(USER, stored.getCreatedBy());
        assertNotNull(stored.getGmtCreate());

        stored.setName("AD FS 灾备");
        stored.setEnabled(0);
        stored.setDescription(null);
        assertEquals(1, idpDao.update(stored));

        FederationIdpDO updated = idpDao.findById(stored.getId());
        assertEquals("AD FS 灾备", updated.getName());
        assertEquals(0, updated.getEnabled());
        assertNull(updated.getDescription());

        assertEquals(List.of(stored.getId()), idpDao.listAll().stream().map(FederationIdpDO::getId).toList());
        assertEquals(1, idpDao.deleteById(stored.getId()));
        assertNull(idpDao.findById(stored.getId()));
    }

    @Test
    void connectionLookupsCoverNameEnabledFlagAndIdpCount() {
        Long idpId = insertIdp("Entra 租户 A");
        Long samlId = insertConnection(idpId, samlConnection("AD FS 生产", 0));
        Long oidcId = insertConnection(idpId, oidcConnection("Entra 租户 A 登录", 5));
        Long disabledId = insertConnection(idpId, disabledConnection("停用的登录方式", 10));

        assertEquals(List.of(samlId, oidcId, disabledId), connectionDao.listAll().stream()
                .map(FederationConnectionDO::getId).toList());
        assertEquals(List.of(samlId, oidcId), connectionDao.listByEnabled(1).stream()
                .map(FederationConnectionDO::getId).toList());
        assertEquals(disabledId, connectionDao.listByEnabled(0).get(0).getId());
        assertEquals(oidcId, connectionDao.findByName("Entra 租户 A 登录").getId());
        assertNull(connectionDao.findByName("不存在的登录方式"));

        FederationConnectionDO stored = connectionDao.findById(oidcId);
        assertEquals(idpId, stored.getIdpId());
        assertEquals("OIDC", stored.getProtocol());
        assertEquals(1, stored.getEnabled());
        assertEquals(5, stored.getSortOrder());
        assertEquals("https://login.microsoftonline.com/t/v2.0", stored.getIssuer());
        assertEquals("tid:oid", stored.getSubjectClaim());
        assertEquals("kc:v1:ciphertext", stored.getClientSecret());
        assertEquals("tenant-1,tenant-2", stored.getAllowedTenants());
        assertEquals(3, connectionDao.countByIdp(idpId));
        assertEquals(0, connectionDao.countByIdp(disabledId + 100));

        assertEquals(1, connectionDao.deleteById(disabledId));
        assertEquals(2, connectionDao.countByIdp(idpId));
    }

    @Test
    void connectionUpdateRewritesProtocolFieldsWithoutTouchingIdpOrProtocol() {
        Long idpId = insertIdp("Entra 租户 A");
        Long id = insertConnection(idpId, samlConnection("AD FS 生产", 0));

        FederationConnectionDO stored = connectionDao.findById(id);
        stored.setName("AD FS 生产 v2");
        stored.setEnabled(0);
        stored.setIssuer("https://adfs2.corp.example.com/adfs/services/trust");
        stored.setSamlSsoUrl("https://adfs2.corp.example.com/adfs/ls");
        stored.setSamlCert("cert-2");
        stored.setSubjectClaim("employeeId");
        stored.setScopes("openid");

        assertEquals(1, connectionDao.update(stored));

        FederationConnectionDO updated = connectionDao.findById(id);
        assertEquals("AD FS 生产 v2", updated.getName());
        assertEquals(0, updated.getEnabled());
        assertEquals("https://adfs2.corp.example.com/adfs/services/trust", updated.getIssuer());
        assertEquals("https://adfs2.corp.example.com/adfs/ls", updated.getSamlSsoUrl());
        assertEquals("cert-2", updated.getSamlCert());
        assertEquals("employeeId", updated.getSubjectClaim());
        assertEquals("openid", updated.getScopes());
        assertEquals(idpId, updated.getIdpId());
        assertEquals("SAML2", updated.getProtocol());
    }

    @Test
    void connectionNameIsUnique() {
        Long idpId = insertIdp("Entra 租户 A");
        insertConnection(idpId, samlConnection("AD FS 生产", 0));

        assertThrows(DataIntegrityViolationException.class,
                () -> insertConnection(idpId, oidcConnection("AD FS 生产", 1)));
    }

    @Test
    void identityBindingIsKeyedByProviderIssuerAndSubjectOnly() {
        Long idpId = insertIdp("Entra 租户 A");
        Long providerId = insertConnection(idpId, oidcConnection("Entra 租户 A 登录", 0));

        FederatedIdentityDO identity = new FederatedIdentityDO();
        identity.setProviderId(providerId);
        identity.setIssuer("https://login.microsoftonline.com/tenant-1/v2.0");
        identity.setSubject("tenant-1:object-42");
        identity.setUserId(USER);
        identity.setDisplayName("Alice");
        identity.setEmail("alice@corp.example.com");
        identity.setLastLoginAt(new Date());

        assertEquals(1, identityDao.insert(identity));
        assertNotNull(identity.getId());

        FederatedIdentityDO binding = identityDao.findBinding(providerId,
                "https://login.microsoftonline.com/tenant-1/v2.0", "tenant-1:object-42");
        assertEquals(identity.getId(), binding.getId());
        assertEquals(USER, binding.getUserId());
        assertNull(identityDao.findBinding(providerId, "https://other-issuer/v2.0", "tenant-1:object-42"));
        assertNull(identityDao.findBinding(providerId + 1,
                "https://login.microsoftonline.com/tenant-1/v2.0", "tenant-1:object-42"));

        assertEquals(identity.getId(), identityDao.findById(identity.getId()).getId());
        assertEquals(List.of(identity.getId()), identityDao.listByUser(USER).stream()
                .map(FederatedIdentityDO::getId).toList());
        assertEquals(1, identityDao.countByUser(USER));
        assertEquals(0, identityDao.countByUser(OTHER_USER));
        assertEquals(1, identityDao.countByProvider(providerId));
        assertEquals(0, identityDao.countByProvider(providerId + 1));
    }

    @Test
    void sameSubjectFromDifferentIssuersBindsSeparately() {
        Long idpId = insertIdp("AD FS 生产");
        Long providerId = insertConnection(idpId, samlConnection("AD FS 生产", 0));

        insertIdentity(providerId, "https://adfs-a/trust", "alice", USER);
        insertIdentity(providerId, "https://adfs-b/trust", "alice", OTHER_USER);

        assertEquals(1, identityDao.countByUser(USER));
        assertEquals(1, identityDao.countByUser(OTHER_USER));
        assertEquals(2, identityDao.countByProvider(providerId));
    }

    @Test
    void updateLastLoginRefreshesDisplayFieldsOnly() {
        Long idpId = insertIdp("Entra 租户 A");
        Long providerId = insertConnection(idpId, oidcConnection("Entra 租户 A 登录", 0));
        Long identityId = insertIdentity(providerId, "https://issuer/v2.0", "tenant-1:object-42", USER);

        assertEquals(1, identityDao.updateLastLogin(identityId, "Alice Zhang", "alice.zhang@corp.example.com"));

        FederatedIdentityDO updated = identityDao.findById(identityId);
        assertEquals("Alice Zhang", updated.getDisplayName());
        assertEquals("alice.zhang@corp.example.com", updated.getEmail());
        assertNotNull(updated.getLastLoginAt());
        FederatedIdentityDO binding = identityDao.findBinding(providerId, "https://issuer/v2.0", "tenant-1:object-42");
        assertEquals(identityId, binding.getId());
        assertEquals(USER, binding.getUserId());
    }

    @Test
    void identityUniqueKeyRejectsDuplicateBinding() {
        Long idpId = insertIdp("Entra 租户 A");
        Long providerId = insertConnection(idpId, oidcConnection("Entra 租户 A 登录", 0));
        insertIdentity(providerId, "https://issuer/v2.0", "tenant-1:object-42", USER);

        assertThrows(DataIntegrityViolationException.class,
                () -> insertIdentity(providerId, "https://issuer/v2.0", "tenant-1:object-42", OTHER_USER));
    }

    @Test
    void identityDeleteByIdRemovesTheBinding() {
        Long idpId = insertIdp("Entra 租户 A");
        Long providerId = insertConnection(idpId, oidcConnection("Entra 租户 A 登录", 0));
        Long identityId = insertIdentity(providerId, "https://issuer/v2.0", "tenant-1:object-42", USER);

        assertEquals(1, identityDao.deleteById(identityId));

        assertNull(identityDao.findById(identityId));
        assertTrue(identityDao.listByUser(USER).isEmpty());
    }

    private static Long insertIdp(String name) {
        FederationIdpDO idp = new FederationIdpDO();
        idp.setName(name);
        idp.setEnabled(1);
        idp.setCreatedBy(USER);
        idpDao.insert(idp);
        return idp.getId();
    }

    private static Long insertConnection(Long idpId, FederationConnectionDO connection) {
        connection.setIdpId(idpId);
        connectionDao.insert(connection);
        return connection.getId();
    }

    private static Long insertIdentity(Long providerId, String issuer, String subject, Long userId) {
        FederatedIdentityDO identity = new FederatedIdentityDO();
        identity.setProviderId(providerId);
        identity.setIssuer(issuer);
        identity.setSubject(subject);
        identity.setUserId(userId);
        identity.setLastLoginAt(new Date());
        identityDao.insert(identity);
        return identity.getId();
    }

    private static FederationConnectionDO samlConnection(String name, int sortOrder) {
        FederationConnectionDO connection = new FederationConnectionDO();
        connection.setName(name);
        connection.setProtocol("SAML2");
        connection.setEnabled(1);
        connection.setIssuer("https://adfs.corp.example.com/adfs/services/trust");
        connection.setSamlSsoUrl("https://adfs.corp.example.com/adfs/ls");
        connection.setSamlCert("cert-1");
        connection.setSortOrder(sortOrder);
        return connection;
    }

    private static FederationConnectionDO oidcConnection(String name, int sortOrder) {
        FederationConnectionDO connection = new FederationConnectionDO();
        connection.setName(name);
        connection.setProtocol("OIDC");
        connection.setEnabled(1);
        connection.setIssuer("https://login.microsoftonline.com/t/v2.0");
        connection.setOidcAuthorizeUrl("https://login.microsoftonline.com/t/v2.0/authorize");
        connection.setOidcTokenUrl("https://login.microsoftonline.com/t/oauth2/v2.0/token");
        connection.setOidcJwksUrl("https://login.microsoftonline.com/t/discovery/v2.0/keys");
        connection.setClientId("client-1");
        connection.setClientSecret("kc:v1:ciphertext");
        connection.setSubjectClaim("tid:oid");
        connection.setAllowedTenants("tenant-1,tenant-2");
        connection.setSortOrder(sortOrder);
        return connection;
    }

    private static FederationConnectionDO disabledConnection(String name, int sortOrder) {
        FederationConnectionDO connection = oidcConnection(name, sortOrder);
        connection.setEnabled(0);
        return connection;
    }

    private static void executeScript(String resource) throws Exception {
        String sql;
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        execute(sql.split(";"));
    }

    private static void execute(String... sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            for (String item : sql) {
                if (!item.isBlank()) {
                    statement.execute(item);
                }
            }
        }
    }
}
