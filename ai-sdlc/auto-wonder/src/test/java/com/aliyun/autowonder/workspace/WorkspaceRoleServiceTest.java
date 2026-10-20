package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.WorkspaceDomain;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.workspace.dto.WorkspaceRoleRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceRoleServiceTest {
    private WorkspaceRoleDao dao;
    private WorkspaceDao workspaces;
    private AuditLogService audit;
    private WorkspaceRoleService service;
    private WorkspaceRoleDO custom;

    @BeforeEach
    void setup() {
        dao = mock(WorkspaceRoleDao.class);
        workspaces = mock(WorkspaceDao.class);
        audit = mock(AuditLogService.class);
        service = new WorkspaceRoleService(dao, workspaces, audit);
        when(workspaces.findByIdForUpdate(7L)).thenReturn(new WorkspaceDO());
        custom = new WorkspaceRoleDO();
        custom.setId(14L);
        custom.setTenantId(7L);
        custom.setName("交付 / Delivery");
        custom.setAccessLevel("READ_WRITE");
        custom.setReadOnlyDomains("[\"SDLC\"]");
        custom.setVersion(2);
        when(dao.findById(7L, 14L)).thenReturn(custom);
    }

    @AfterEach
    void teardown() { LocaleContextHolder.resetLocaleContext(); }

    @Test
    void createsCustomRoleUnderWorkspaceLockWithNormalizedDomainsAndRequiredAudit() {
        doAnswer(invocation -> { ((WorkspaceRoleDO) invocation.getArgument(0)).setId(14L); return null; })
                .when(dao).insert(any());
        var result = service.create(7L, request("交付 / Delivery", List.of("sdlc", "SDLC"), null), 9L);
        var order = inOrder(workspaces, dao, audit);
        order.verify(workspaces).findByIdForUpdate(7L);
        order.verify(dao).insert(argThat(role -> role.getTenantId() == 7L
                && "READ_WRITE".equals(role.getAccessLevel()) && role.getCode() == null
                && "[\"SDLC\"]".equals(role.getReadOnlyDomains())));
        order.verify(audit).recordRequired(argThat(record -> "WORKSPACE_ROLE_CREATED".equals(record.getAction())
                && record.getTenantId() == 7L && record.getActorId() == 9L));
        assertEquals("交付 / Delivery", result.getName());
        assertFalse(result.isAdministrator());
        assertFalse(result.isBuiltIn());
        assertEquals(List.of(WorkspaceDomain.SDLC), result.getReadOnlyDomains());
    }

    @Test
    void updatesUseSubmittedVersionAndClearOnlyWriteRestrictions() {
        when(dao.update(any())).thenReturn(1);
        service.update(7L, 14L, request("新名称", List.of(), 2), 9L);
        verify(dao).update(argThat(role -> role.getVersion() == 2 && role.getReadOnlyDomains() == null
                && "READ_WRITE".equals(role.getAccessLevel()) && "新名称".equals(role.getName())));
        verify(audit).recordRequired(any());
    }

    @Test
    void staleUpdateAndDeleteDoNotAudit() {
        assertKey("workspace.role.version_conflict", () -> service.update(7L, 14L, request("name", List.of(), 1), 9L));
        assertKey("workspace.role.version_conflict", () -> service.delete(7L, 14L, 1, 9L));
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsMissingAndCrossWorkspaceRolesBeforeMutation() {
        assertKey("workspace.role.not_found", () -> service.requireRole(7L, null));
        when(workspaces.findByIdForUpdate(8L)).thenReturn(new WorkspaceDO());
        assertKey("workspace.role.not_found", () -> service.update(8L, 14L, request("name", List.of(), 2), 9L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "READ_ONLY", "READ_WRITE"})
    void builtinRolesCannotBeDeleted(String code) {
        custom.setCode(code);
        assertKey("workspace.role.builtin_protected", () -> service.delete(7L, 14L, 2, 9L));
        verify(dao, never()).update(any());
        verify(dao, never()).softDelete(anyLong(), anyLong(), anyInt(), anyLong());
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "READ_ONLY"})
    void viewerAndAdministratorPermissionsRemainFixed(String code) {
        custom.setCode(code);
        custom.setAccessLevel(code);
        assertKey("workspace.role.builtin_permissions_fixed",
                () -> service.update(7L, 14L, request(null, List.of("SDLC"), 2), 9L));
        verify(dao, never()).update(any());
        verifyNoInteractions(audit);
    }

    @Test
    void collaboratorUpdatesOnlyDomainsWithTheSameIdentityAndRequiredAudit() {
        custom.setCode("READ_WRITE");
        custom.setName(null);
        when(dao.update(any())).thenReturn(1);
        var updated = service.update(7L, 14L, request(null, List.of("sdlc", "SDLC"), 2), 9L);
        assertTrue(updated.isBuiltIn());
        assertFalse(updated.isAdministrator());
        assertEquals("READ_WRITE", updated.getCode());
        assertNull(updated.getName());
        assertEquals(List.of(WorkspaceDomain.SDLC), updated.getReadOnlyDomains());
        verify(dao).update(argThat(role -> role.getVersion() == 2 && role.getModifierId() == 9L
                && "READ_WRITE".equals(role.getCode()) && "READ_WRITE".equals(role.getAccessLevel())
                && role.getName() == null && "[\"SDLC\"]".equals(role.getReadOnlyDomains())));
        verify(audit).recordRequired(argThat(record -> "WORKSPACE_ROLE_UPDATED".equals(record.getAction())
                && record.getTenantId() == 7L && record.getTargetId() == 14L));
        assertNull(service.update(7L, 14L, request(null, List.of(), 2), 9L).getName());
        assertNull(custom.getReadOnlyDomains());
    }

    @ParameterizedTest
    @ValueSource(strings = {"读写者", "Collaborator", "renamed", ""})
    void collaboratorRejectsEverySubmittedNameWithoutMatchingTranslatedText(String name) {
        custom.setCode("READ_WRITE");
        custom.setName(null);
        var error = assertThrows(BizException.class,
                () -> service.update(7L, 14L, request(name, List.of(), 2), 9L));
        assertEquals("workspace.role.builtin_name_fixed", error.getMessageKey());
        LocaleContextHolder.setLocale(Locale.US);
        assertEquals("Built-in role names cannot be changed.", Result.fail(error).getMessage());
        LocaleContextHolder.setLocale(Locale.CHINA);
        assertEquals("内置角色的名称不能修改。", Result.fail(error).getMessage());
        assertEquals("Built-in role names cannot be changed", error.getMessage());
        assertNull(custom.getName());
        verify(dao, never()).update(any());
        verifyNoInteractions(audit);
    }

    @Test
    void collaboratorRequiresValidDomainsAndVersionAndRejectsConcurrentUpdates() {
        custom.setCode("READ_WRITE");
        assertThrows(BizException.class, () -> service.update(7L, 14L, request(null, List.of(), null), 9L));
        assertKey("workspace.role.invalid", () -> service.update(7L, 14L, request(null, null, 2), 9L));
        assertKey("workspace.role.domain_invalid", () -> service.update(7L, 14L, request(null, List.of("UNKNOWN"), 2), 9L));
        verify(dao, never()).update(any());
        assertKey("workspace.role.version_conflict", () -> service.update(7L, 14L, request(null, List.of(), 1), 9L));
        verifyNoInteractions(audit);
    }

    @Test
    void assignedRolesCannotBeDeletedAndUnusedRolesRequireVersion() {
        when(dao.countAssignments(7L, 14L)).thenReturn(1);
        assertKey("workspace.role.in_use", () -> service.delete(7L, 14L, 2, 9L));
        verify(dao, never()).softDelete(anyLong(), anyLong(), anyInt(), anyLong());
        when(dao.countAssignments(7L, 14L)).thenReturn(0);
        when(dao.softDelete(7L, 14L, 2, 9L)).thenReturn(1);
        service.delete(7L, 14L, 2, 9L);
        verify(audit).recordRequired(argThat(record -> "WORKSPACE_ROLE_DELETED".equals(record.getAction())));
    }

    @Test
    void namesAndDomainPayloadAreValidatedAtTheBoundary() {
        assertKey("workspace.role.invalid", () -> service.create(7L, null, 9L));
        for (String name : new String[]{null, " ", "x".repeat(81)}) {
            assertKey("workspace.role.invalid", () -> service.create(7L, request(name, List.of(), null), 9L));
        }
        assertKey("workspace.role.invalid", () -> service.create(7L, request("name", null, null), 9L));
        assertKey("workspace.role.domain_invalid", () -> service.create(7L, request("name", List.of("UNKNOWN"), null), 9L));
        verify(dao, never()).insert(any());
        verifyNoInteractions(audit);
    }

    @Test
    void duplicateNameAndAuditFailuresPropagateWithoutSuccess() {
        doThrow(new DuplicateKeyException("index")).when(dao).insert(any());
        assertKey("workspace.role.name_duplicate", () -> service.create(7L, request("name", List.of(), null), 9L));
        when(dao.update(any())).thenThrow(new DuplicateKeyException("index"));
        assertKey("workspace.role.name_duplicate", () -> service.update(7L, 14L, request("name", List.of(), 2), 9L));
        verifyNoInteractions(audit);
        doReturn(1).when(dao).update(any());
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordRequired(any(AuditLogRecord.class));
        assertThrows(IllegalStateException.class, () -> service.update(7L, 14L, request("name", List.of(), 2), 9L));
    }

    @Test
    void builtinSeedingAndAdminPermissionsAreExplicit() {
        service.seed(7L, 9L);
        verify(dao, times(3)).insert(any());
        verify(dao).insert(argThat(role -> "READ_ONLY".equals(role.getCode())
                && role.getReadOnlyDomains() == null));
        verify(dao).insert(argThat(role -> "ADMIN".equals(role.getCode()) && role.getReadOnlyDomains() == null));
        custom.setAccessLevel("ADMIN");
        custom.setCode("ADMIN");
        custom.setName(null);
        when(dao.listByTenant(7L)).thenReturn(List.of(custom));
        var result = service.list(7L).get(0);
        assertTrue(result.isAdministrator());
        assertTrue(result.getReadOnlyDomains().isEmpty());
        assertNull(result.getName());
        assertKey("workspace.role.migration_required", () -> service.builtIn(7L, WorkspaceAccessLevel.ADMIN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[\"SDLC\"]"})
    void viewerUsesCurrentDomainListWithoutRewritingStoredPermissions(String storedDomains) {
        custom.setAccessLevel("READ_ONLY");
        custom.setCode("READ_ONLY");
        custom.setReadOnlyDomains(storedDomains.isEmpty() ? null : storedDomains);
        when(dao.listByTenant(7L)).thenReturn(List.of(custom));

        assertEquals(List.of(WorkspaceDomain.values()), service.list(7L).get(0).getReadOnlyDomains());
        assertEquals(storedDomains.isEmpty() ? null : storedDomains, custom.getReadOnlyDomains());
        verify(dao, never()).update(any());
    }

    @Test
    void systemErrorsLocalizeWhileUserNamesAndDiagnosticsStayRaw() {
        when(dao.countAssignments(7L, 14L)).thenReturn(1);
        BizException error = assertThrows(BizException.class, () -> service.delete(7L, 14L, 2, 9L));
        String diagnostic = error.getMessage();
        LocaleContextHolder.setLocale(Locale.US);
        assertEquals("This role is assigned to members. Assign them another role first.", Result.fail(error).getMessage());
        LocaleContextHolder.setLocale(Locale.CHINA);
        assertNotEquals(diagnostic, Result.fail(error).getMessage());
        assertEquals(diagnostic, error.getMessage());
        when(dao.listByTenant(7L)).thenReturn(List.of(custom));
        assertEquals("交付 / Delivery", service.list(7L).get(0).getName());
    }

    @Test
    void allRoleMutationsAreTransactional() throws Exception {
        assertNotNull(WorkspaceRoleService.class.getMethod("create", long.class, WorkspaceRoleRequest.class, long.class).getAnnotation(Transactional.class));
        assertNotNull(WorkspaceRoleService.class.getMethod("update", long.class, long.class, WorkspaceRoleRequest.class, long.class).getAnnotation(Transactional.class));
        assertNotNull(WorkspaceRoleService.class.getMethod("delete", long.class, long.class, int.class, long.class).getAnnotation(Transactional.class));
    }

    private static WorkspaceRoleRequest request(String name, List<String> domains, Integer version) {
        var request = new WorkspaceRoleRequest();
        request.setName(name);
        request.setReadOnlyDomains(domains);
        request.setVersion(version);
        return request;
    }

    private static void assertKey(String key, org.junit.jupiter.api.function.Executable action) {
        assertEquals(key, assertThrows(BizException.class, action).getMessageKey());
    }
}
