package com.aliyun.autowonder.taskpackage;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DaemonTaskPackageControllerTest {

    private DaemonUploadAuthenticator authenticator;
    private DispatchDao dispatchDao;
    private ObjectStorage storage;
    private DaemonTaskPackageController controller;

    @BeforeEach
    void setUp() {
        authenticator = mock(DaemonUploadAuthenticator.class);
        dispatchDao = mock(DispatchDao.class);
        storage = mock(ObjectStorage.class);
        controller = new DaemonTaskPackageController(authenticator, dispatchDao, storage);
    }

    @Test
    void refreshesActiveDispatchPackageUrlFromPersistedOssRef() {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(99L);
        dispatch.setStatus(DispatchStatus.RUNNING);
        dispatch.setPackageOssRef("bucket/task-packages/99.zip");
        when(dispatchDao.findById(99L)).thenReturn(dispatch);
        when(storage.presignGet(dispatch.getPackageOssRef(), 600)).thenReturn("https://oss/new-url");

        ResponseEntity<?> response = controller.refresh(99L, "tok");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("https://oss/new-url", body.get("downloadUrl"));
        assertEquals(600, body.get("expiresInSeconds"));
    }

    @Test
    void rejectsTerminalDispatchWithoutPresigning() {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(99L);
        dispatch.setStatus(DispatchStatus.SUCCEEDED);
        dispatch.setPackageOssRef("bucket/task-packages/99.zip");
        when(dispatchDao.findById(99L)).thenReturn(dispatch);

        ResponseEntity<?> response = controller.refresh(99L, "tok");

        assertEquals(409, response.getStatusCode().value());
        verifyNoInteractions(storage);
    }
}
