package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.auth.federation.dto.CreateConnectionRequest;
import com.aliyun.autowonder.auth.federation.dto.CreateIdpRequest;
import com.aliyun.autowonder.auth.federation.dto.FederationConnectionVO;
import com.aliyun.autowonder.auth.federation.dto.FederationIdpVO;
import com.aliyun.autowonder.auth.federation.dto.UpdateConnectionRequest;
import com.aliyun.autowonder.auth.federation.dto.UpdateIdpRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class FederationAdminControllerTest {
    private static final Long ADMIN = 10000L;
    private static final String DENIED = "access.admin.federation_denied";

    private final FederationRegistryService registryService = mock(FederationRegistryService.class);
    private final SystemAdminService systemAdminService = mock(SystemAdminService.class);
    private final FederationAdminController controller =
            new FederationAdminController(registryService, systemAdminService);

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void readAndWriteEndpointsForwardTheRegistryResult() {
        AutoWonderContext.get().setUserId(ADMIN);
        List<FederationIdpVO> idps = List.of(idp(1L));
        List<FederationConnectionVO> connections = List.of(connection(7L));
        CreateIdpRequest createIdp = new CreateIdpRequest();
        UpdateIdpRequest updateIdp = new UpdateIdpRequest();
        CreateConnectionRequest createConnection = new CreateConnectionRequest();
        UpdateConnectionRequest updateConnection = new UpdateConnectionRequest();
        FederationIdpVO createdIdp = idp(2L);
        FederationIdpVO updatedIdp = idp(1L);
        FederationConnectionVO createdConnection = connection(8L);
        FederationConnectionVO updatedConnection = connection(7L);
        when(registryService.listIdps()).thenReturn(idps);
        when(registryService.createIdp(createIdp, ADMIN)).thenReturn(createdIdp);
        when(registryService.updateIdp(1L, updateIdp)).thenReturn(updatedIdp);
        when(registryService.listConnections()).thenReturn(connections);
        when(registryService.createConnection(createConnection)).thenReturn(createdConnection);
        when(registryService.updateConnection(7L, updateConnection)).thenReturn(updatedConnection);

        assertSame(idps, controller.listIdps().getData());
        assertSame(createdIdp, controller.createIdp(createIdp).getData());
        assertSame(updatedIdp, controller.updateIdp(1L, updateIdp).getData());
        assertSame(connections, controller.listConnections().getData());
        assertSame(createdConnection, controller.createConnection(createConnection).getData());
        assertSame(updatedConnection, controller.updateConnection(7L, updateConnection).getData());
        controller.deleteIdp(1L);
        controller.deleteConnection(7L);
    }

    @Test
    void everyEndpointUsesItsOwnAuthorizationLabel() {
        AutoWonderContext.get().setUserId(ADMIN);
        when(registryService.listIdps()).thenReturn(List.of());
        when(registryService.listConnections()).thenReturn(List.of());
        when(registryService.createIdp(any(), eq(ADMIN))).thenReturn(idp(2L));
        when(registryService.updateIdp(eq(1L), any())).thenReturn(idp(1L));
        when(registryService.createConnection(any())).thenReturn(connection(8L));
        when(registryService.updateConnection(eq(7L), any())).thenReturn(connection(7L));

        controller.listIdps();
        controller.createIdp(new CreateIdpRequest());
        controller.updateIdp(1L, new UpdateIdpRequest());
        controller.deleteIdp(1L);
        controller.listConnections();
        controller.createConnection(new CreateConnectionRequest());
        controller.updateConnection(7L, new UpdateConnectionRequest());
        controller.deleteConnection(7L);

        verify(systemAdminService).requireSystemAdmin(ADMIN, "查看 SSO 身份提供方", DENIED);
        verify(systemAdminService).requireSystemAdmin(ADMIN, "创建 SSO 身份提供方", DENIED);
        verify(systemAdminService).requireSystemAdmin(ADMIN, "更新 SSO 身份提供方", DENIED);
        verify(systemAdminService).requireSystemAdmin(ADMIN, "删除 SSO 身份提供方", DENIED);
        verify(systemAdminService).requireSystemAdmin(ADMIN, "查看 SSO 登录方式", DENIED);
        verify(systemAdminService).requireSystemAdmin(ADMIN, "创建 SSO 登录方式", DENIED);
        verify(systemAdminService).requireSystemAdmin(ADMIN, "更新 SSO 登录方式", DENIED);
        verify(systemAdminService).requireSystemAdmin(ADMIN, "删除 SSO 登录方式", DENIED);
        verifyNoMoreInteractions(systemAdminService);
    }

    @Test
    void unauthorizedCallersNeverReachTheRegistry() {
        AutoWonderContext.get().setUserId(ADMIN);
        doThrow(new BizException(ErrorCode.UNAUTHORIZED))
                .when(systemAdminService).requireSystemAdmin(any(), any(), any());

        assertThrows(BizException.class, () -> controller.listIdps());
        assertThrows(BizException.class, () -> controller.createIdp(new CreateIdpRequest()));
        assertThrows(BizException.class, () -> controller.updateIdp(1L, new UpdateIdpRequest()));
        assertThrows(BizException.class, () -> controller.deleteIdp(1L));
        assertThrows(BizException.class, () -> controller.listConnections());
        assertThrows(BizException.class, () -> controller.createConnection(new CreateConnectionRequest()));
        assertThrows(BizException.class, () -> controller.updateConnection(7L, new UpdateConnectionRequest()));
        assertThrows(BizException.class, () -> controller.deleteConnection(7L));

        verifyNoInteractions(registryService);
    }

    private static FederationIdpVO idp(Long id) {
        FederationIdpVO vo = new FederationIdpVO();
        vo.setId(id);
        return vo;
    }

    private static FederationConnectionVO connection(Long id) {
        FederationConnectionVO vo = new FederationConnectionVO();
        vo.setId(id);
        return vo;
    }
}
