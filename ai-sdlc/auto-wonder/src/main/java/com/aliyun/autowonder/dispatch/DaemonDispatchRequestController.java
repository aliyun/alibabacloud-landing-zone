package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/daemon")
public class DaemonDispatchRequestController {
    private final DispatchRequestService requests;
    private final DaemonUploadAuthenticator auth;
    public DaemonDispatchRequestController(DispatchRequestService requests, DaemonUploadAuthenticator auth) {
        this.requests = requests; this.auth = auth;
    }
    @PostMapping("/dispatches/{dispatchId}/requests")
    public Object create(@PathVariable long dispatchId, @RequestHeader("Authorization") String authorization,
            @RequestBody DispatchRequestService.Create input) {
        authenticate(dispatchId, authorization);
        return requests.create(dispatchId, input);
    }
    @GetMapping("/dispatches/{dispatchId}/requests/{id}")
    public Object get(@PathVariable long dispatchId, @PathVariable long id, @RequestHeader("Authorization") String authorization) {
        return requests.get(authenticate(dispatchId, authorization), dispatchId, id);
    }
    @PostMapping("/dispatches/{dispatchId}/requests/{id}/cancel")
    public Object cancel(@PathVariable long dispatchId, @PathVariable long id, @RequestHeader("Authorization") String authorization) {
        requests.cancel(authenticate(dispatchId, authorization), dispatchId, id);
        return java.util.Map.of("status", "CANCELED");
    }
    private long authenticate(long dispatchId, String authorization) {
        var result = auth.authenticate(dispatchId, bearer(authorization));
        if (!result.isSuccess()) throw new BizException(ErrorCode.UNAUTHORIZED);
        return result.getTenantId();
    }
    private String bearer(String value) {
        if (value == null || !value.startsWith("Bearer ")) throw new BizException(ErrorCode.UNAUTHORIZED);
        return value.substring(7);
    }
}
