package com.aliyun.autowonder.taskdata;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only identity view for a workitem dispatch. Business scripts decide
 * which external provider/project they require; the platform only returns the
 * links already associated with this workitem.
 */
@RestController
@RequestMapping("/api/daemon/dispatches/{dispatchId}/workitem-identity")
public class DaemonWorkitemIdentityController {

    private final DaemonUploadAuthenticator authenticator;
    private final ExternalWorkitemLinkDao linkDao;

    public DaemonWorkitemIdentityController(DaemonUploadAuthenticator authenticator,
                                            ExternalWorkitemLinkDao linkDao) {
        this.authenticator = authenticator;
        this.linkDao = linkDao;
    }

    @GetMapping
    public ResponseEntity<?> get(@PathVariable long dispatchId,
                                 @RequestParam("token") String token) {
        DaemonUploadAuthenticator.AuthResult auth = authenticator.authenticate(dispatchId, token);
        if (!auth.isSuccess()) {
            return ResponseEntity.status(401).build();
        }
        if (auth.getSourceType() != ExecutionSourceType.WORKITEM) {
            return ResponseEntity.status(409).body(Map.of("error", "workitem identity is unavailable for this dispatch"));
        }
        List<Map<String, Object>> links = new ArrayList<>();
        for (ExternalWorkitemLinkDO link : linkDao.listByWorkitem(auth.getTenantId(), auth.getWorkitemId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("provider", link.getProvider());
            item.put("externalProjectId", link.getExternalProjectId());
            item.put("externalWorkitemId", link.getExternalWorkitemId());
            item.put("externalUrl", link.getExternalUrl());
            links.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workitemId", auth.getWorkitemId());
        response.put("externalLinks", links);
        return ResponseEntity.ok(response);
    }
}
