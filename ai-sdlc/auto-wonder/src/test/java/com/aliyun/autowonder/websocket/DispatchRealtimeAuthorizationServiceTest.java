package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchRealtimeAuthorizationServiceTest {

    private final DispatchDao dispatchDao = mock(DispatchDao.class);
    private final WorkspaceMemberDao memberDao = mock(WorkspaceMemberDao.class);
    private DispatchRealtimeAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new DispatchRealtimeAuthorizationService(dispatchDao, memberDao);
    }

    @Test
    void supportsDispatchPrefix() {
        assertTrue(service.supports("dispatch:123"));
        assertFalse(service.supports("conversation:123"));
        assertFalse(service.supports(null));
    }

    @Test
    void authorizesValidDispatchInWorkspace() {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(10L);
        dispatch.setTenantId(100L);
        when(dispatchDao.findById(10L)).thenReturn(dispatch);

        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setStatus(0);
        member.setAccessLevel("READ_ONLY");
        when(memberDao.findByWorkspaceAndUser(100L, 42L)).thenReturn(member);

        assertTrue(service.authorize(100L, 42L, "dispatch:10"));
    }

    @Test
    void deniesWhenDispatchNotFound() {
        when(dispatchDao.findById(10L)).thenReturn(null);
        assertFalse(service.authorize(100L, 42L, "dispatch:10"));
    }

    @Test
    void deniesWhenDispatchBelongsToDifferentWorkspace() {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(10L);
        dispatch.setTenantId(200L);
        when(dispatchDao.findById(10L)).thenReturn(dispatch);
        assertFalse(service.authorize(100L, 42L, "dispatch:10"));
        verifyNoInteractions(memberDao);
    }

    @Test
    void deniesWhenMemberNotFound() {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(10L);
        dispatch.setTenantId(100L);
        when(dispatchDao.findById(10L)).thenReturn(dispatch);
        when(memberDao.findByWorkspaceAndUser(100L, 42L)).thenReturn(null);
        assertFalse(service.authorize(100L, 42L, "dispatch:10"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {1, 2, -1})
    void deniesWhenMemberInactive(Integer status) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(10L);
        dispatch.setTenantId(100L);
        when(dispatchDao.findById(10L)).thenReturn(dispatch);

        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setStatus(status);
        member.setAccessLevel("READ_ONLY");
        when(memberDao.findByWorkspaceAndUser(100L, 42L)).thenReturn(member);
        assertFalse(service.authorize(100L, 42L, "dispatch:10"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "UNKNOWN"})
    void deniesActiveMemberWithoutReadAccess(String accessLevel) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setTenantId(100L);
        when(dispatchDao.findById(10L)).thenReturn(dispatch);
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setStatus(0);
        member.setAccessLevel(accessLevel);
        when(memberDao.findByWorkspaceAndUser(100L, 42L)).thenReturn(member);
        assertFalse(service.authorize(100L, 42L, "dispatch:10"));
    }

    @Test
    void deniesNullChannel() {
        assertFalse(service.authorize(100L, 42L, null));
    }

    @Test
    void deniesMalformedChannel() {
        assertFalse(service.authorize(100L, 42L, "dispatch:abc"));
        assertFalse(service.authorize(100L, 42L, "dispatch:"));
        assertFalse(service.authorize(100L, 42L, "dispatch:-1"));
    }

    @Test
    void deniesNonPositiveWorkspaceOrUser() {
        assertFalse(service.authorize(0L, 42L, "dispatch:10"));
        assertFalse(service.authorize(100L, 0L, "dispatch:10"));
    }
}
