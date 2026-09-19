package com.aliyun.autowonder.environment;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EnvironmentVariableServiceTest {

    private final EnvironmentVariableDao dao = mock(EnvironmentVariableDao.class);
    private final SecretCrypto keyCenter = mock(SecretCrypto.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EnvironmentVariableService service = new EnvironmentVariableService(dao, keyCenter, audit);

    @Test
    void createEncryptsValueAndScopesRecordToTenant() throws Exception {
        when(keyCenter.encrypt("plain-secret")).thenReturn("kc:v1:opaque");
        when(dao.findActiveByNameIgnoreCase(41L, "API_TOKEN")).thenReturn(null);
        doAnswer(invocation -> {
            EnvironmentVariableDO row = invocation.getArgument(0);
            row.setId(9L);
            row.setVersion(0);
            return 1;
        }).when(dao).insert(any());
        when(dao.findActiveById(41L, 9L))
                .thenReturn(row(9L, 41L, "API_TOKEN", "kc:v1:opaque", 0));

        EnvironmentVariableVO result = service.create(41L, 7L,
                new CreateEnvironmentVariableRequest("API_TOKEN", "plain-secret", "token"));

        EnvironmentVariableDO inserted = captureInserted();
        assertEquals(41L, inserted.getTenantId());
        assertEquals(7L, inserted.getCreatorId());
        assertEquals("kc:v1:opaque", inserted.getCredentialRef());
        assertEquals("**", result.getValue());
        String json = new ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("plain-secret"));
        assertFalse(json.contains("kc:v1:opaque"));
        assertFalse(json.contains("credentialRef"));
    }

    @Test
    void listAlwaysReturnsFixedMaskWithoutDecrypting() {
        EnvironmentVariableDO first = row(1L, 41L, "SHORT", "kc:a", 0);
        EnvironmentVariableDO second = row(2L, 41L, "LONG", "kc:long-ciphertext", 0);
        when(dao.listActive(41L)).thenReturn(List.of(first, second));

        List<EnvironmentVariableVO> result = service.list(41L);

        assertEquals(List.of("**", "**"), result.stream().map(EnvironmentVariableVO::getValue).toList());
        verifyNoInteractions(keyCenter);
    }

    @Test
    void revealDecryptsOnlyTenantScopedRecord() {
        when(dao.findActiveById(41L, 9L)).thenReturn(row(9L, 41L, "TOKEN", "kc:ref", 2));
        when(keyCenter.decrypt("kc:ref")).thenReturn("secret");

        assertEquals("secret", service.reveal(41L, 7L, 9L).getValue());
        verify(dao).findActiveById(41L, 9L);
    }

    @Test
    void crossTenantRecordIsIndistinguishableFromMissing() {
        when(dao.findActiveById(99L, 9L)).thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.reveal(99L, 7L, 9L));

        assertEquals("33001", error.getCode());
        verifyNoInteractions(keyCenter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1TOKEN", "HAS-DASH", "WITH SPACE", ""})
    void rejectsInvalidNames(String name) {
        BizException error = assertThrows(BizException.class,
                () -> service.create(41L, 7L, new CreateEnvironmentVariableRequest(name, "v", null)));
        assertEquals("33002", error.getCode());
        verifyNoInteractions(keyCenter);
    }

    @Test
    void rejectsNamesLongerThan128BeforeEncryptionOrPersistence() {
        BizException error = assertThrows(BizException.class, () -> service.create(41L, 7L,
                new CreateEnvironmentVariableRequest("A".repeat(129), "v", null)));

        assertEquals("33002", error.getCode());
        verifyNoInteractions(keyCenter);
        verify(dao, never()).insert(any());
    }

    @Test
    void createReturnsTimestampsReadBackFromPersistedRow() {
        Date created = new Date(5_000);
        Date modified = new Date(6_000);
        when(keyCenter.encrypt("value")).thenReturn("kc:ref");
        when(dao.findActiveByNameIgnoreCase(41L, "TOKEN")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<EnvironmentVariableDO>getArgument(0).setId(9L);
            return 1;
        }).when(dao).insert(any());
        EnvironmentVariableDO persisted = row(9L, 41L, "TOKEN", "kc:ref", 0);
        persisted.setGmtCreate(created);
        persisted.setGmtModified(modified);
        when(dao.findActiveById(41L, 9L)).thenReturn(persisted);

        EnvironmentVariableVO result = service.create(41L, 7L,
                new CreateEnvironmentVariableRequest("TOKEN", "value", null));

        assertEquals(created, result.getGmtCreate());
        assertEquals(modified, result.getGmtModified());
        verify(dao).findActiveById(41L, 9L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "AUTOWONDER_TOKEN", "autowonder_internal",
            "CODEX_HOME", "codex_home",
            "CLAUDE_CONFIG_DIR", "claude_config_dir",
            "QODER_CONFIG_DIR", "qoder_config_dir",
            "QODERCN_CONFIG_DIR", "qodercn_config_dir",
            "QODER_INTEGRATION_ID", "qoder_integration_id",
            "QODER_HOST_SERVICE_NAME", "qoder_host_service_name"
    })
    void rejectsReservedNamesCaseInsensitively(String name) {
        BizException error = assertThrows(BizException.class,
                () -> service.create(41L, 7L, new CreateEnvironmentVariableRequest(name, "v", null)));
        assertEquals("33003", error.getCode());
        verifyNoInteractions(keyCenter);
    }

    @Test
    void rejectsCaseInsensitiveConflict() {
        when(dao.findActiveByNameIgnoreCase(41L, "token"))
                .thenReturn(row(5L, 41L, "TOKEN", "kc:r", 0));

        BizException error = assertThrows(BizException.class,
                () -> service.create(41L, 7L,
                        new CreateEnvironmentVariableRequest("token", "v", null)));

        assertEquals("33004", error.getCode());
        verifyNoInteractions(keyCenter);
    }

    @Test
    void metadataOnlyUpdateNeverReadsOrReencryptsValue() {
        EnvironmentVariableDO current = row(9L, 41L, "OLD", "kc:existing", 3);
        when(dao.findActiveById(41L, 9L)).thenReturn(current);
        when(dao.findActiveByNameIgnoreCase(41L, "RENAMED")).thenReturn(null);
        when(dao.updateMetadata(41L, 9L, "RENAMED", "new description", 7L, 3)).thenReturn(1);

        service.update(41L, 7L, 9L,
                new UpdateEnvironmentVariableRequest("RENAMED", "new description", false, null));

        verify(dao).updateMetadata(41L, 9L, "RENAMED", "new description", 7L, 3);
        verify(dao, never()).updateWithValue(anyLong(), anyLong(), anyString(), any(), anyString(), anyLong(), anyInt());
        verifyNoInteractions(keyCenter);
    }

    @Test
    void explicitValueUpdateEncryptsEvenAnEmptyValue() {
        EnvironmentVariableDO current = row(9L, 41L, "TOKEN", "kc:existing", 3);
        when(dao.findActiveById(41L, 9L)).thenReturn(current);
        when(keyCenter.encrypt("")).thenReturn("kc:empty");
        when(dao.updateWithValue(41L, 9L, "TOKEN", null, "kc:empty", 7L, 3)).thenReturn(1);

        service.update(41L, 7L, 9L,
                new UpdateEnvironmentVariableRequest("TOKEN", null, true, ""));

        verify(keyCenter).encrypt("");
        verify(dao).updateWithValue(41L, 9L, "TOKEN", null, "kc:empty", 7L, 3);
    }

    @Test
    void updateReturnsTimestampAndVersionReadBackFromPersistedRow() {
        EnvironmentVariableDO current = row(9L, 41L, "TOKEN", "kc:existing", 3);
        EnvironmentVariableDO persisted = row(9L, 41L, "RENAMED", "kc:existing", 4);
        persisted.setGmtModified(new Date(9_000));
        when(dao.findActiveById(41L, 9L)).thenReturn(current, persisted);
        when(dao.findActiveByNameIgnoreCase(41L, "RENAMED")).thenReturn(null);
        when(dao.updateMetadata(41L, 9L, "RENAMED", null, 7L, 3)).thenReturn(1);

        EnvironmentVariableVO result = service.update(41L, 7L, 9L,
                new UpdateEnvironmentVariableRequest("RENAMED", null, false, null));

        assertEquals(new Date(9_000), result.getGmtModified());
        assertEquals(4, result.getVersion());
        verify(dao, times(2)).findActiveById(41L, 9L);
    }

    @Test
    void deleteReportsOnlyActiveOnlineOrEditingReferences() {
        EnvironmentVariableDO current = row(9L, 41L, "TOKEN", "kc:existing", 3);
        when(dao.findActiveByIdForUpdate(41L, 9L)).thenReturn(current);
        when(dao.listActiveReferences(41L, 9L)).thenReturn(List.of(
                new EnvironmentVariableReference(12L, "Reviewer", 22L, 4, "ONLINE"),
                new EnvironmentVariableReference(13L, "Builder", 23L, 5, "EDITING")));

        BizException error = assertThrows(BizException.class, () -> service.delete(41L, 7L, 9L));

        assertEquals("33005", error.getCode());
        assertTrue(error.getMessage().contains("Reviewer(#12) 在线版本 v4"));
        assertTrue(error.getMessage().contains("Builder(#13) 编辑草稿 v5"));
        verify(dao, never()).softDelete(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void deleteUsesTenantAndOptimisticVersion() {
        EnvironmentVariableDO locked = row(9L, 41L, "TOKEN", "kc:r", 3);
        when(dao.findActiveByIdForUpdate(41L, 9L)).thenReturn(locked);
        when(dao.listActiveReferences(41L, 9L)).thenReturn(List.of());
        when(dao.softDelete(41L, 9L, 3, 7L)).thenReturn(1);

        service.delete(41L, 7L, 9L);

        verify(dao).findActiveByIdForUpdate(41L, 9L);
        verify(dao, never()).findActiveById(anyLong(), anyLong());
        verify(dao).softDelete(41L, 9L, 3, 7L);
    }

    @Test
    void updateAndDeleteSurfaceOptimisticLockConflicts() {
        EnvironmentVariableDO current = row(9L, 41L, "TOKEN", "kc:r", 3);
        when(dao.findActiveById(41L, 9L)).thenReturn(current);
        when(dao.updateMetadata(41L, 9L, "TOKEN", null, 7L, 3)).thenReturn(0);
        BizException updateError = assertThrows(BizException.class, () -> service.update(41L, 7L, 9L,
                new UpdateEnvironmentVariableRequest("TOKEN", null, false, null)));
        assertEquals("33006", updateError.getCode());

        when(dao.findActiveByIdForUpdate(41L, 9L)).thenReturn(current);
        when(dao.listActiveReferences(41L, 9L)).thenReturn(List.of());
        when(dao.softDelete(41L, 9L, 3, 7L)).thenReturn(0);
        BizException deleteError = assertThrows(BizException.class, () -> service.delete(41L, 7L, 9L));
        assertEquals("33006", deleteError.getCode());
    }

    @Test
    void updateTranslatesDatabaseNameRaceToBusinessConflict() {
        EnvironmentVariableDO current = row(9L, 41L, "TOKEN", "kc:r", 3);
        when(dao.findActiveById(41L, 9L)).thenReturn(current);
        when(dao.findActiveByNameIgnoreCase(41L, "RENAMED")).thenReturn(null);
        when(dao.updateMetadata(41L, 9L, "RENAMED", null, 7L, 3))
                .thenThrow(new DuplicateKeyException("race"));

        BizException error = assertThrows(BizException.class, () -> service.update(41L, 7L, 9L,
                new UpdateEnvironmentVariableRequest("RENAMED", null, false, null)));

        assertEquals("33004", error.getCode());
    }

    @Test
    void listQueryDoesNotFetchCredentialReferences() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/mapping/EnvironmentVariableDao.xml"));
        int start = xml.indexOf("<select id=\"listActive\"");
        int end = xml.indexOf("</select>", start);
        String listStatement = xml.substring(start, end);

        assertFalse(listStatement.contains("credential_ref"));
    }

    @Test
    void lockedLookupIsTenantScopedAndUsesForUpdate() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/mapping/EnvironmentVariableDao.xml"));
        int start = xml.indexOf("<select id=\"findActiveByIdForUpdate\"");
        int end = xml.indexOf("</select>", start);

        assertTrue(start >= 0, "locked lookup mapper statement is required");
        String statement = xml.substring(start, end);
        assertTrue(statement.contains("tenant_id = #{tenantId}"));
        assertTrue(statement.contains("id = #{id}"));
        assertTrue(statement.contains("FOR UPDATE"));
    }

    @Test
    void createAndRevealAuditMetadataWithoutSensitiveValues() {
        when(keyCenter.encrypt("plain-secret")).thenReturn("kc:v1:opaque");
        when(dao.findActiveByNameIgnoreCase(41L, "API_TOKEN")).thenReturn(null);
        doAnswer(invocation -> {
            EnvironmentVariableDO row = invocation.getArgument(0);
            row.setId(9L);
            return 1;
        }).when(dao).insert(any());
        when(dao.findActiveById(41L, 9L))
                .thenReturn(row(9L, 41L, "API_TOKEN", "kc:v1:opaque", 0));
        service.create(41L, 7L,
                new CreateEnvironmentVariableRequest("API_TOKEN", "plain-secret", null));

        when(dao.findActiveById(41L, 9L)).thenReturn(row(9L, 41L, "API_TOKEN", "kc:v1:opaque", 0));
        when(keyCenter.decrypt("kc:v1:opaque")).thenReturn("plain-secret");
        service.reveal(41L, 7L, 9L);

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(audit, times(2)).recordRequired(captor.capture());
        assertEquals(List.of("CREATE", "REVEAL"),
                captor.getAllValues().stream().map(AuditLogRecord::getAction).toList());
        for (AuditLogRecord record : captor.getAllValues()) {
            String detail = record.getDetail().toString();
            assertFalse(detail.contains("plain-secret"));
            assertFalse(detail.contains("kc:v1:opaque"));
        }
    }

    private EnvironmentVariableDO captureInserted() {
        var captor = org.mockito.ArgumentCaptor.forClass(EnvironmentVariableDO.class);
        verify(dao).insert(captor.capture());
        return captor.getValue();
    }

    private static EnvironmentVariableDO row(long id, long tenantId, String name, String ref, int version) {
        EnvironmentVariableDO row = new EnvironmentVariableDO();
        row.setId(id);
        row.setTenantId(tenantId);
        row.setName(name);
        row.setCredentialRef(ref);
        row.setVersion(version);
        row.setGmtModified(new Date(1234));
        return row;
    }
}
