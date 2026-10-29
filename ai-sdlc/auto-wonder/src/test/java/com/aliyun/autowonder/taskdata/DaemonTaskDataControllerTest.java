package com.aliyun.autowonder.taskdata;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.taskdata.dto.TaskDataRecordVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DaemonTaskDataControllerTest {

    @Test
    void workitemDispatchCanWriteAndScheduledRunCanOnlyRead() {
        DaemonUploadAuthenticator authenticator = mock(DaemonUploadAuthenticator.class);
        TaskDataService service = mock(TaskDataService.class);
        DaemonTaskDataController controller = new DaemonTaskDataController(authenticator, service);

        when(authenticator.authenticate(1, "token"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10, 20, 30, null,
                        ExecutionSourceType.WORKITEM));
        when(authenticator.isMutationFenced(1)).thenReturn(false);
        TaskDataRecordVO record = new TaskDataRecordVO();
        record.setRecordKey("a:1");
        record.setVersion(1);
        when(service.put(anyLong(), anyString(), anyString(), anyLong(), any(), anyInt(), anyLong()))
                .thenReturn(record);
        var response = controller.put(1, "token",
                Map.of("namespace", "links", "key", "a:1", "value", Map.of("pr", 1), "expectedVersion", 0));
        assertEquals(HttpStatus.OK, response.getStatusCode());

        when(authenticator.authenticate(2, "token"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10, 99, 30, null,
                        ExecutionSourceType.SCHEDULED_TASK_RUN));
        var forbidden = controller.put(2, "token",
                Map.of("namespace", "links", "key", "a:1", "value", Map.of("pr", 1), "expectedVersion", 0));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        when(service.get(10, "links", "a:1")).thenReturn(record);
        var read = controller.get(2, "token", "links", "a:1", null, 50);
        assertEquals(HttpStatus.OK, read.getStatusCode());
    }

    @Test
    void payloadCannotAssertAnotherWorkitemAsOwner() {
        DaemonUploadAuthenticator authenticator = mock(DaemonUploadAuthenticator.class);
        TaskDataService service = mock(TaskDataService.class);
        DaemonTaskDataController controller = new DaemonTaskDataController(authenticator, service);
        when(authenticator.authenticate(1, "token"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10, 38570, 30, null,
                        ExecutionSourceType.WORKITEM));
        when(authenticator.isMutationFenced(1)).thenReturn(false);

        var response = controller.put(1, "token", Map.of(
                "namespace", "links", "key", "a:1", "ownerWorkitemId", 99999,
                "value", Map.of("workitemId", 99999), "expectedVersion", 0));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void unauthenticatedRequestIsRejected() {
        DaemonUploadAuthenticator authenticator = mock(DaemonUploadAuthenticator.class);
        when(authenticator.authenticate(1, "bad"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.fail());
        DaemonTaskDataController controller = new DaemonTaskDataController(authenticator, mock(TaskDataService.class));
        var response = controller.get(1, "bad", "links", null, null, 50);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
