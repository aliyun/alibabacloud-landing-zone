package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.auth.federation.dto.PlatformAuthConfigVO;
import com.aliyun.autowonder.auth.federation.dto.UpdatePlatformAuthConfigRequest;
import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAuthConfigServiceTest {
    private static final Long PROVIDER_ID = 7L;
    private static final Long IDP_ID = 3L;

    private PlatformAuthConfigDao configDao;
    private FederationConnectionDao connectionDao;
    private FederationIdpDao idpDao;
    private PlatformAuthConfigService service;

    @BeforeEach
    void setUp() {
        configDao = mock(PlatformAuthConfigDao.class);
        connectionDao = mock(FederationConnectionDao.class);
        idpDao = mock(FederationIdpDao.class);
        service = new PlatformAuthConfigService(configDao, connectionDao, idpDao);
    }

    @Test
    void missingConfigKeepsRegistrationOpenAndNoDefaultConnection() {
        when(configDao.find()).thenReturn(null);

        assertTrue(service.registrationEnabled());
        assertNull(service.defaultProviderId());
        assertFalse(service.isDefaultProvider(PROVIDER_ID));
        assertEquals(Boolean.TRUE, service.current().getRegistrationEnabled());
    }

    @Test
    void updateClosesRegistrationAndKeepsTheSingletonRow() {
        PlatformAuthConfigDO stored = config(1, null);
        when(configDao.lock()).thenReturn(stored);
        when(configDao.find()).thenReturn(stored);
        UpdatePlatformAuthConfigRequest request = new UpdatePlatformAuthConfigRequest();
        request.setRegistrationEnabled(false);

        PlatformAuthConfigVO updated = service.update(request);

        assertEquals(Boolean.FALSE, updated.getRegistrationEnabled());
        ArgumentCaptor<PlatformAuthConfigDO> saved = ArgumentCaptor.forClass(PlatformAuthConfigDO.class);
        verify(configDao).update(saved.capture());
        assertEquals(0, saved.getValue().getRegistrationEnabled());
        assertNull(saved.getValue().getDefaultProviderId());
    }

    @Test
    void firstUpdateCreatesTheSingletonRowWhenNoRecordExists() {
        PlatformAuthConfigDO created = config(1, null);
        when(configDao.lock()).thenReturn(null);
        when(configDao.find()).thenReturn(created);
        UpdatePlatformAuthConfigRequest request = new UpdatePlatformAuthConfigRequest();
        request.setRegistrationEnabled(false);

        service.update(request);

        ArgumentCaptor<PlatformAuthConfigDO> inserted = ArgumentCaptor.forClass(PlatformAuthConfigDO.class);
        verify(configDao).insert(inserted.capture());
        assertEquals(PlatformAuthConfigDO.SINGLETON_ID, inserted.getValue().getId());
        ArgumentCaptor<PlatformAuthConfigDO> updated = ArgumentCaptor.forClass(PlatformAuthConfigDO.class);
        verify(configDao).update(updated.capture());
        assertEquals(0, updated.getValue().getRegistrationEnabled());
    }

    @Test
    void concurrentFirstWriteReloadsTheRowCreatedByTheOtherTransaction() {
        PlatformAuthConfigDO created = config(1, null);
        when(configDao.lock()).thenReturn(null, created);
        when(configDao.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(configDao.find()).thenReturn(created);
        UpdatePlatformAuthConfigRequest request = new UpdatePlatformAuthConfigRequest();
        request.setRegistrationEnabled(false);

        service.update(request);

        verify(configDao).update(any());
    }

    @Test
    void defaultConnectionMustExistAndBeEnabledWithItsIdp() {
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(connection(1));
        when(idpDao.findById(IDP_ID)).thenReturn(idp(1));

        assertThrows(BizException.class, () -> service.setDefaultProvider(null));
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(null);
        assertThrows(BizException.class, () -> service.setDefaultProvider(PROVIDER_ID));
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(connection(0));
        assertThrows(BizException.class, () -> service.setDefaultProvider(PROVIDER_ID));
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(connection(1));
        when(idpDao.findById(IDP_ID)).thenReturn(idp(0));
        assertThrows(BizException.class, () -> service.setDefaultProvider(PROVIDER_ID));
        verify(configDao, never()).update(any());
    }

    @Test
    void settingAndClearingTheDefaultConnectionKeepsOnlyOneReference() {
        PlatformAuthConfigDO stored = config(1, null);
        when(connectionDao.findById(PROVIDER_ID)).thenReturn(connection(1));
        when(idpDao.findById(IDP_ID)).thenReturn(idp(1));
        when(configDao.lock()).thenReturn(stored);
        when(configDao.find()).thenReturn(stored);

        PlatformAuthConfigVO set = service.setDefaultProvider(PROVIDER_ID);
        assertEquals(PROVIDER_ID, set.getDefaultProviderId());
        assertTrue(service.isDefaultProvider(PROVIDER_ID));

        service.clearDefaultProviderIfMatches(99L);
        assertEquals(PROVIDER_ID, stored.getDefaultProviderId());

        service.clearDefaultProviderIfMatches(PROVIDER_ID);
        assertNull(stored.getDefaultProviderId());
    }

    @Test
    void updateCanClearTheDefaultReferenceExplicitly() {
        PlatformAuthConfigDO stored = config(1, PROVIDER_ID);
        when(configDao.lock()).thenReturn(stored);
        when(configDao.find()).thenReturn(stored);
        UpdatePlatformAuthConfigRequest request = new UpdatePlatformAuthConfigRequest();
        request.setClearDefaultProvider(true);

        service.update(request);

        assertNull(stored.getDefaultProviderId());
    }

    private static PlatformAuthConfigDO config(int registrationEnabled, Long defaultProviderId) {
        PlatformAuthConfigDO config = new PlatformAuthConfigDO();
        config.setId(PlatformAuthConfigDO.SINGLETON_ID);
        config.setRegistrationEnabled(registrationEnabled);
        config.setDefaultProviderId(defaultProviderId);
        return config;
    }

    private static FederationConnectionDO connection(int enabled) {
        FederationConnectionDO connection = new FederationConnectionDO();
        connection.setId(PROVIDER_ID);
        connection.setIdpId(IDP_ID);
        connection.setEnabled(enabled);
        return connection;
    }

    private static FederationIdpDO idp(int enabled) {
        FederationIdpDO idp = new FederationIdpDO();
        idp.setId(IDP_ID);
        idp.setEnabled(enabled);
        return idp;
    }
}
