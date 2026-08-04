package com.aliyun.autowonder.setting;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.setting.dto.SettingVO;
import com.aliyun.autowonder.setting.dto.UpdateSettingsRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemSettingServiceTest {

    private SystemSettingDao settingDao;
    private SecretCrypto secretCrypto;
    private SystemSettingService service;

    @BeforeEach
    void setUp() {
        settingDao = mock(SystemSettingDao.class);
        secretCrypto = mock(SecretCrypto.class);
        when(secretCrypto.encrypt(anyString())).thenAnswer(invocation ->
                "enc:v1:" + invocation.getArgument(0, String.class));
        when(secretCrypto.decrypt(anyString())).thenAnswer(invocation ->
                invocation.getArgument(0, String.class));
        when(secretCrypto.mask(anyString())).thenReturn("re****yz");
        service = new SystemSettingService(settingDao, secretCrypto);
    }

    @Test
    void listByGroupReturnsSettings() {
        SystemSettingDO s = new SystemSettingDO();
        s.setId(1L);
        s.setSettingGroup("AI");
        s.setSettingKey("model");
        s.setValueJson("\"claude-sonnet-4-6\"");
        s.setIsSecret(0);
        when(settingDao.listByGroup(1L, "AI")).thenReturn(List.of(s));

        List<SettingVO> result = service.listByGroup("AI", 1L);
        assertEquals(1, result.size());
        assertEquals("model", result.get(0).getKey());
        assertFalse(result.get(0).isSecret());
    }

    @Test
    void listByGroupMasksSecrets() {
        SystemSettingDO s = new SystemSettingDO();
        s.setId(1L);
        s.setSettingGroup("AI");
        s.setSettingKey("api_key");
        s.setIsSecret(1);
        s.setCredentialRef("ref:sk-ant-abc123xyz");
        when(settingDao.listByGroup(1L, "AI")).thenReturn(List.of(s));

        List<SettingVO> result = service.listByGroup("AI", 1L);
        assertEquals(1, result.size());
        assertTrue(result.get(0).isSecret());
        assertTrue(result.get(0).getValueJson().contains("****"));
    }

    @Test
    void invalidGroupThrows() {
        BizException ex = assertThrows(BizException.class, () -> service.listByGroup("INVALID", 1L));
        assertEquals(ErrorCode.SETTING_GROUP_INVALID.getCode(), ex.getCode());
    }

    @Test
    void systemGroupIsValidForSettingsPage() {
        when(settingDao.listByGroup(1L, "SYSTEM")).thenReturn(List.of());

        assertDoesNotThrow(() -> service.listByGroup("SYSTEM", 1L));
    }

    @Test
    void updateGroupCreatesNewSetting() {
        when(settingDao.findByUk(1L, "AI", "timeout")).thenReturn(null);

        UpdateSettingsRequest req = new UpdateSettingsRequest();
        UpdateSettingsRequest.SettingItem item = new UpdateSettingsRequest.SettingItem();
        item.setKey("timeout");
        item.setValueJson("\"30\"");
        item.setSecret(false);
        req.setItems(List.of(item));

        service.updateGroup("AI", req, 1L, 2L);
        verify(settingDao).insert(argThat(s ->
                s.getTenantId() == 1L
                        && "AI".equals(s.getSettingGroup())
                        && "timeout".equals(s.getSettingKey())
                        && "\"30\"".equals(s.getValueJson())
                        && s.getIsSecret() == 0
                        && s.getCreatorId() == 2L
                        && s.getModifierId() == 2L));
    }

    @Test
    void updateGroupUpdatesExisting() {
        SystemSettingDO existing = new SystemSettingDO();
        existing.setId(1L);
        existing.setSettingGroup("AI");
        existing.setSettingKey("timeout");
        existing.setIsSecret(0);
        when(settingDao.findByUk(1L, "AI", "timeout")).thenReturn(existing);

        UpdateSettingsRequest req = new UpdateSettingsRequest();
        UpdateSettingsRequest.SettingItem item = new UpdateSettingsRequest.SettingItem();
        item.setKey("timeout");
        item.setValueJson("\"60\"");
        item.setSecret(false);
        req.setItems(List.of(item));

        service.updateGroup("AI", req, 1L, 2L);
        verify(settingDao).update(1L, 1L, "\"60\"", 0, null, 2L);
    }

    @Test
    void updateGroupCreatesSecretSetting() {
        when(settingDao.findByUk(1L, "AI", "api_key")).thenReturn(null);

        UpdateSettingsRequest req = new UpdateSettingsRequest();
        UpdateSettingsRequest.SettingItem item = new UpdateSettingsRequest.SettingItem();
        item.setKey("api_key");
        item.setValueJson("sk-secret-value");
        item.setSecret(true);
        req.setItems(List.of(item));

        service.updateGroup("AI", req, 1L, 2L);
        verify(settingDao).insert(argThat(s ->
                s.getIsSecret() == 1 && s.getCredentialRef() != null && s.getValueJson() == null));
    }

    @Test
    void updateGroupUpdatesSecretExisting() {
        SystemSettingDO existing = new SystemSettingDO();
        existing.setId(5L);
        existing.setSettingGroup("AI");
        existing.setSettingKey("api_key");
        existing.setIsSecret(1);
        existing.setCredentialRef("old-ref");
        when(settingDao.findByUk(1L, "AI", "api_key")).thenReturn(existing);

        UpdateSettingsRequest req = new UpdateSettingsRequest();
        UpdateSettingsRequest.SettingItem item = new UpdateSettingsRequest.SettingItem();
        item.setKey("api_key");
        item.setValueJson("new-secret-value");
        item.setSecret(true);
        req.setItems(List.of(item));

        service.updateGroup("AI", req, 1L, 2L);
        verify(settingDao).update(eq(5L), eq(1L), isNull(), eq(1), notNull(), eq(2L));
    }

    @Test
    void updateGroupNullItemsDoesNothing() {
        UpdateSettingsRequest req = new UpdateSettingsRequest();
        req.setItems(null);
        assertDoesNotThrow(() -> service.updateGroup("AI", req, 1L, 2L));
        verify(settingDao, never()).insert(any());
        verify(settingDao, never()).update(anyLong(), anyLong(), any(), anyInt(), any(), anyLong());
    }

    @Test
    void getDecryptedValueForSecret() {
        SystemSettingDO s = new SystemSettingDO();
        s.setId(1L);
        s.setIsSecret(1);
        s.setCredentialRef("ref:12345");
        when(settingDao.findByUk(1L, "AI", "api_key")).thenReturn(s);

        String val = service.getDecryptedValue("AI", "api_key", 1L);
        assertEquals("ref:12345", val);
    }

    @Test
    void getDecryptedValueForNonSecret() {
        SystemSettingDO s = new SystemSettingDO();
        s.setId(1L);
        s.setIsSecret(0);
        s.setValueJson("\"plain-value\"");
        when(settingDao.findByUk(1L, "AI", "timeout")).thenReturn(s);

        String val = service.getDecryptedValue("AI", "timeout", 1L);
        assertEquals("\"plain-value\"", val);
    }

    @Test
    void getDecryptedValueReturnsNullWhenNotFound() {
        when(settingDao.findByUk(1L, "AI", "missing")).thenReturn(null);
        assertNull(service.getDecryptedValue("AI", "missing", 1L));
    }

    @Test
    void mapperUsesActualSystemSettingColumns() throws Exception {
        String mapper = Files.readString(Path.of("src/main/resources/mapping/SystemSettingDao.xml"));

        assertTrue(mapper.contains("setting_group"));
        assertTrue(mapper.contains("setting_key"));
        assertTrue(mapper.contains("ORDER BY setting_key"));
        assertFalse(mapper.contains("`group`"));
        assertFalse(mapper.contains("`key`"));
        assertFalse(mapper.contains("#{group}"));
        assertFalse(mapper.contains("#{key}"));
    }

    @Test
    void schemaUsesActualSystemSettingColumns() throws Exception {
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));
        String block = schema.substring(
                schema.indexOf("CREATE TABLE IF NOT EXISTS `system_setting`"),
                schema.indexOf("-- 外部项目绑定"));

        assertTrue(block.contains("`setting_group`"));
        assertTrue(block.contains("`setting_key`"));
        assertTrue(block.contains("UNIQUE KEY `uk_setting` (`tenant_id`, `setting_group`, `setting_key`)"));
        assertFalse(block.contains("`group`"));
        assertFalse(block.contains("`key`"));
    }

}
