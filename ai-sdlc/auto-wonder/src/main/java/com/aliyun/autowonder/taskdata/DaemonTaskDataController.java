package com.aliyun.autowonder.taskdata;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.taskdata.dto.TaskDataPageVO;
import com.aliyun.autowonder.taskdata.dto.TaskDataRecordVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/daemon/dispatches/{dispatchId}/task-data")
public class DaemonTaskDataController {

    private final DaemonUploadAuthenticator authenticator;
    private final TaskDataService taskDataService;

    public DaemonTaskDataController(DaemonUploadAuthenticator authenticator, TaskDataService taskDataService) {
        this.authenticator = authenticator;
        this.taskDataService = taskDataService;
    }

    @GetMapping
    public ResponseEntity<?> get(@PathVariable long dispatchId,
                                 @RequestParam("token") String token,
                                 @RequestParam("namespace") String namespace,
                                 @RequestParam(value = "key", required = false) String key,
                                 @RequestParam(value = "afterId", required = false) Long afterId,
                                 @RequestParam(value = "limit", defaultValue = "50") int limit) {
        DaemonUploadAuthenticator.AuthResult auth = authenticate(dispatchId, token);
        if (!auth.isSuccess()) {
            return ResponseEntity.status(401).build();
        }
        try {
            if (key != null && !key.isBlank()) {
                return ResponseEntity.ok(taskDataService.get(auth.getTenantId(), namespace, key));
            }
            TaskDataPageVO page = taskDataService.list(auth.getTenantId(), namespace, afterId, limit);
            return ResponseEntity.ok(page);
        } catch (TaskDataService.TaskDataNotFoundException e) {
            return ResponseEntity.status(404).body(error("record not found"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<?> put(@PathVariable long dispatchId,
                                 @RequestParam("token") String token,
                                 @RequestBody Map<String, Object> body) {
        DaemonUploadAuthenticator.AuthResult auth = authenticate(dispatchId, token);
        if (!auth.isSuccess()) {
            return ResponseEntity.status(401).build();
        }
        if (auth.getSourceType() != ExecutionSourceType.WORKITEM
                || authenticator.isMutationFenced(dispatchId)) {
            return ResponseEntity.status(403).body(error("task data is writable only by a workitem dispatch"));
        }
        Long assertedOwner = longValue(body.get("ownerWorkitemId"));
        if (assertedOwner != null && assertedOwner != auth.getWorkitemId()) {
            return ResponseEntity.status(403).body(error("ownerWorkitemId does not match dispatch workitem"));
        }
        String namespace = stringValue(body.get("namespace"));
        String key = stringValue(body.get("key"));
        Object value = body.get("value");
        Integer expectedVersion = intValue(body.get("expectedVersion"));
        try {
            TaskDataRecordVO record = taskDataService.put(auth.getTenantId(), namespace, key,
                    auth.getWorkitemId(), value, expectedVersion, auth.getAgentId());
            return ResponseEntity.ok(record);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(error(e.getMessage()));
        } catch (TaskDataConflictException e) {
            Map<String, Object> response = error(e.getMessage());
            if (e.current() != null) {
                response.put("currentVersion", e.current().getVersion());
                response.put("currentOwnerWorkitemId", e.current().getOwnerWorkitemId());
            }
            return ResponseEntity.status(409).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    private DaemonUploadAuthenticator.AuthResult authenticate(long dispatchId, String token) {
        return authenticator.authenticate(dispatchId, token);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Integer intValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
