package com.aliyun.autowonder.taskpackage;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/daemon")
public class DaemonTaskPackageController {

    private static final Logger log = LoggerFactory.getLogger(DaemonTaskPackageController.class);
    private static final int DOWNLOAD_TTL_SECONDS = 600;
    private static final Set<String> REFRESHABLE_STATUSES = Set.of(
            DispatchStatus.DISPATCHED,
            DispatchStatus.ACKED,
            DispatchStatus.RUNNING
    );

    private final DaemonUploadAuthenticator authenticator;
    private final DispatchDao dispatchDao;
    private final ObjectStorage storage;

    public DaemonTaskPackageController(DaemonUploadAuthenticator authenticator,
                                       DispatchDao dispatchDao,
                                       ObjectStorage storage) {
        this.authenticator = authenticator;
        this.dispatchDao = dispatchDao;
        this.storage = storage;
    }

    @PostMapping("/dispatches/{dispatchId}/package-url")
    public ResponseEntity<?> refresh(@PathVariable long dispatchId,
                                     @RequestParam("token") String token) {
        if (!authenticator.authenticate(dispatchId, token).isSuccess()) {
            return ResponseEntity.status(401).build();
        }
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null) {
            return ResponseEntity.notFound().build();
        }
        if (!REFRESHABLE_STATUSES.contains(dispatch.getStatus())
                || dispatch.getPackageOssRef() == null
                || dispatch.getPackageOssRef().isBlank()) {
            return ResponseEntity.status(409).body(Map.of("error", "package URL is not refreshable"));
        }
        String downloadUrl = storage.presignGet(dispatch.getPackageOssRef(), DOWNLOAD_TTL_SECONDS);
        log.info("taskpackage download url refreshed dispatchId={} ossRef={} ttlSeconds={} downloadUrl={}",
                dispatchId, dispatch.getPackageOssRef(), DOWNLOAD_TTL_SECONDS, downloadUrl);
        return ResponseEntity.ok(Map.of(
                "downloadUrl", downloadUrl,
                "expiresInSeconds", DOWNLOAD_TTL_SECONDS
        ));
    }
}
