package com.aliyun.autowonder.environment;

import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefDao;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentEnvironmentVariableResolverTest {
    private static final long TENANT_ID = 100L;
    private static final long VERSION_ID = 200L;

    private AgentEnvironmentVariableRefDao refDao;
    private SecretCrypto secretCrypto;
    private AgentEnvironmentVariableResolver resolver;

    @BeforeEach
    void setUp() {
        refDao = mock(AgentEnvironmentVariableRefDao.class);
        secretCrypto = mock(SecretCrypto.class);
        resolver = new AgentEnvironmentVariableResolver(refDao, secretCrypto);
    }

    @Test
    void usesOneJoinedQueryForCompleteDeterministicallySortedSnapshot() {
        when(refDao.listResolutionSnapshot(TENANT_ID, VERSION_ID))
                .thenReturn(List.of(row(2L, "ZETA", "ref-z"), row(1L, "ALPHA", "ref-a")));
        when(secretCrypto.decrypt("ref-a")).thenReturn("value-a");
        when(secretCrypto.decrypt("ref-z")).thenReturn("value-z");

        Map<String, String> snapshot = resolver.resolve(TENANT_ID, VERSION_ID);

        assertEquals(Map.of("ALPHA", "value-a", "ZETA", "value-z"), snapshot);
        assertEquals(List.of("ALPHA", "ZETA"), new ArrayList<>(snapshot.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("EXTRA", "value"));
        verify(refDao).listResolutionSnapshot(TENANT_ID, VERSION_ID);
        verify(refDao, never()).listByVersion(anyLong(), anyLong());
    }

    @Test
    void returnsEmptySnapshotWhenVersionHasNoReferences() {
        when(refDao.listResolutionSnapshot(TENANT_ID, VERSION_ID)).thenReturn(List.of());
        assertEquals(Map.of(), resolver.resolve(TENANT_ID, VERSION_ID));
        verifyNoInteractions(secretCrypto);
    }

    @Test
    void decryptsCurrentValueOnEveryInvocation() {
        when(refDao.listResolutionSnapshot(TENANT_ID, VERSION_ID))
                .thenReturn(List.of(row(1L, "TOKEN", "ref-old")),
                        List.of(row(1L, "TOKEN", "ref-new")));
        when(secretCrypto.decrypt("ref-old")).thenReturn("old-value");
        when(secretCrypto.decrypt("ref-new")).thenReturn("new-value");
        assertEquals("old-value", resolver.resolve(TENANT_ID, VERSION_ID).get("TOKEN"));
        assertEquals("new-value", resolver.resolve(TENANT_ID, VERSION_ID).get("TOKEN"));
        verify(refDao, times(2)).listResolutionSnapshot(TENANT_ID, VERSION_ID);
    }

    @Test
    void publishedUnbindRemovesVariableFromNextCompleteSnapshot() {
        when(refDao.listResolutionSnapshot(TENANT_ID, VERSION_ID))
                .thenReturn(List.of(row(1L, "TOKEN", "ref-one")), List.of());
        when(secretCrypto.decrypt("ref-one")).thenReturn("value-one");
        assertEquals(Map.of("TOKEN", "value-one"), resolver.resolve(TENANT_ID, VERSION_ID));
        assertEquals(Map.of(), resolver.resolve(TENANT_ID, VERSION_ID));
    }

    @Test
    void rejectsMissingDeletedOrCrossTenantJoinedRowsBeforeDecrypting() {
        AgentEnvironmentVariableSnapshotRow invalid = row(1L, null, null);
        invalid.setVariableId(null);
        invalid.setVariableTenantId(null);
        when(refDao.listResolutionSnapshot(TENANT_ID, VERSION_ID)).thenReturn(List.of(invalid));
        EnvironmentSnapshotResolutionException error = assertThrows(
                EnvironmentSnapshotResolutionException.class,
                () -> resolver.resolve(TENANT_ID, VERSION_ID));
        assertFalse(error.getMessage().contains("credential"));
        verifyNoInteractions(secretCrypto);
    }

    @Test
    void failsClosedBeforeDecryptingOnDuplicateName() {
        when(refDao.listResolutionSnapshot(TENANT_ID, VERSION_ID)).thenReturn(
                List.of(row(1L, "TOKEN", "ref-one"), row(2L, "token", "ref-two")));
        assertThrows(IllegalStateException.class, () -> resolver.resolve(TENANT_ID, VERSION_ID));
        verifyNoInteractions(secretCrypto);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "AUTOWONDER_SECRET", "autowonder_secret",
            "CODEX_HOME", "codex_home",
            "CLAUDE_CONFIG_DIR", "claude_config_dir",
            "QODER_CONFIG_DIR", "qoder_config_dir",
            "QODERCN_CONFIG_DIR", "qodercn_config_dir",
            "QODER_INTEGRATION_ID", "qoder_integration_id",
            "QODER_HOST_SERVICE_NAME", "qoder_host_service_name"
    })
    void failsClosedBeforeDecryptingOnReservedNameCaseInsensitively(String name) {
        when(refDao.listResolutionSnapshot(TENANT_ID, VERSION_ID))
                .thenReturn(List.of(row(1L, name, "sensitive-ref")));
        EnvironmentSnapshotResolutionException error = assertThrows(
                EnvironmentSnapshotResolutionException.class,
                () -> resolver.resolve(TENANT_ID, VERSION_ID));
        assertFalse(error.getMessage().contains("sensitive-ref"));
        verifyNoInteractions(secretCrypto);
    }

    @Test
    void decryptionFailureDoesNotExposeValueOrCredentialReference() {
        when(refDao.listResolutionSnapshot(TENANT_ID, VERSION_ID))
                .thenReturn(List.of(row(1L, "TOKEN", "sensitive-ref")));
        when(secretCrypto.decrypt("sensitive-ref"))
                .thenThrow(new IllegalStateException("failed sensitive-ref secret-value"));
        EnvironmentSnapshotResolutionException error = assertThrows(
                EnvironmentSnapshotResolutionException.class,
                () -> resolver.resolve(TENANT_ID, VERSION_ID));
        assertFalse(error.getMessage().contains("sensitive-ref"));
        assertFalse(error.getMessage().contains("secret-value"));
        assertNull(error.getCause());
    }

    @Test
    void nullDecryptionResultFailsClosed() {
        when(refDao.listResolutionSnapshot(TENANT_ID, VERSION_ID))
                .thenReturn(List.of(row(1L, "TOKEN", "sensitive-ref")));
        when(secretCrypto.decrypt("sensitive-ref")).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> resolver.resolve(TENANT_ID, VERSION_ID));
    }

    private AgentEnvironmentVariableSnapshotRow row(long variableId, String name,
            String credentialRef) {
        AgentEnvironmentVariableSnapshotRow row = new AgentEnvironmentVariableSnapshotRow();
        row.setRefTenantId(TENANT_ID);
        row.setAgentVersionId(VERSION_ID);
        row.setEnvironmentVariableId(variableId);
        row.setVariableId(variableId);
        row.setVariableTenantId(TENANT_ID);
        row.setName(name);
        row.setCredentialRef(credentialRef);
        return row;
    }
}
