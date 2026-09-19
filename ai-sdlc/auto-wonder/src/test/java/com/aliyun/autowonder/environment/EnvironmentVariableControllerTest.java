package com.aliyun.autowonder.environment;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EnvironmentVariableControllerTest {

    private final EnvironmentVariableService service = mock(EnvironmentVariableService.class);
    private final EnvironmentVariableController controller = new EnvironmentVariableController(service);

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void readEndpointsRequireReadOnlyAndMutationsRequireAdmin() throws Exception {
        RequireWorkspaceAccess classAccess = EnvironmentVariableController.class
                .getAnnotation(RequireWorkspaceAccess.class);
        assertNotNull(classAccess);
        assertEquals(WorkspaceAccessLevel.READ_ONLY, classAccess.value());

        assertNull(method("list").getAnnotation(RequireWorkspaceAccess.class));
        assertNull(method("reveal", Long.class).getAnnotation(RequireWorkspaceAccess.class));
        assertAdmin(method("create", CreateEnvironmentVariableRequest.class));
        assertAdmin(method("update", Long.class, UpdateEnvironmentVariableRequest.class));
        assertAdmin(method("delete", Long.class));
    }

    @Test
    void revealSetsNoStoreAndForwardsTenantScope() {
        context(41L, 7L);
        when(service.reveal(41L, 7L, 9L)).thenReturn(new EnvironmentVariableValueVO("secret"));

        ResponseEntity<?> response = controller.reveal(9L);

        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(service).reveal(41L, 7L, 9L);
    }

    @Test
    void mutationsForwardWorkspaceAndActorWithoutLoggingCredentialReferenceInDtos() {
        context(41L, 7L);
        CreateEnvironmentVariableRequest create = new CreateEnvironmentVariableRequest("TOKEN", "secret", null);
        UpdateEnvironmentVariableRequest update = new UpdateEnvironmentVariableRequest("TOKEN", null, false, null);

        controller.create(create);
        controller.update(9L, update);
        controller.delete(9L);

        verify(service).create(41L, 7L, create);
        verify(service).update(41L, 7L, 9L, update);
        verify(service).delete(41L, 7L, 9L);
        assertFalse(java.util.Arrays.stream(EnvironmentVariableVO.class.getDeclaredFields())
                .anyMatch(field -> "credentialRef".equals(field.getName())));
    }

    @Test
    void updateRejectsJsonThatOmitsExplicitUpdateValueChoice() throws Exception {
        context(41L, 7L);

        mvc().perform(put("/api/environment-variables/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TOKEN\",\"description\":\"metadata\"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void updateAcceptsExplicitFalseAsMetadataOnlyThroughJsonBoundary() throws Exception {
        context(41L, 7L);

        mvc().perform(put("/api/environment-variables/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TOKEN\",\"updateValue\":false}"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(UpdateEnvironmentVariableRequest.class);
        verify(service).update(eq(41L), eq(7L), eq(9L), captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().isUpdateValue());
        assertNull(captor.getValue().getValue());
    }

    @Test
    void updateAcceptsExplicitTrueValueReplacementThroughJsonBoundary() throws Exception {
        context(41L, 7L);

        mvc().perform(put("/api/environment-variables/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TOKEN\",\"updateValue\":true,\"value\":\"replacement\"}"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(UpdateEnvironmentVariableRequest.class);
        verify(service).update(eq(41L), eq(7L), eq(9L), captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().isUpdateValue());
        assertEquals("replacement", captor.getValue().getValue());
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Method method(String name, Class<?>... parameters) throws NoSuchMethodException {
        return EnvironmentVariableController.class.getMethod(name, parameters);
    }

    private static void assertAdmin(Method method) {
        RequireWorkspaceAccess access = method.getAnnotation(RequireWorkspaceAccess.class);
        assertNotNull(access);
        assertEquals(WorkspaceAccessLevel.ADMIN, access.value());
    }

    private static void context(long tenantId, long userId) {
        AutoWonderContext.get().setCurrentWorkspaceId(tenantId);
        AutoWonderContext.get().setUserId(userId);
    }
}
