package com.aliyun.autowonder.branding;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.branding.dto.LogoUploadVO;
import com.aliyun.autowonder.branding.dto.PlatformBrandingVO;
import com.aliyun.autowonder.branding.dto.UpdatePlatformBrandingRequest;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.storage.ObjectStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.TimeUnit;
@RestController
@RequestMapping("/api/platform/branding")
public class PlatformBrandingController {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformBrandingController.class);

    private final PlatformBrandingService brandingService;
    private final SystemAdminService systemAdminService;

    public PlatformBrandingController(
            PlatformBrandingService brandingService,
            SystemAdminService systemAdminService) {
        this.brandingService = brandingService;
        this.systemAdminService = systemAdminService;
    }

    @GetMapping("/public")
    public Result<PlatformBrandingVO> publicConfig() {
        return Result.ok(brandingService.publicConfig());
    }

    @GetMapping
    public Result<PlatformBrandingVO> adminConfig() {
        boolean canManage = systemAdminService.isFirstActiveUser(AutoWonderContext.get().getUserId());
        return Result.ok(brandingService.adminConfig(canManage));
    }

    @PutMapping
    public Result<PlatformBrandingVO> update(@RequestBody UpdatePlatformBrandingRequest request) {
        systemAdminService.requireFirstActiveUser(
                AutoWonderContext.get().getUserId(), "更新平台品牌配置");
        return Result.ok(brandingService.update(AutoWonderContext.get().getUserId(), request));
    }

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<LogoUploadVO> uploadLogo(@RequestParam("file") MultipartFile file) {
        systemAdminService.requireFirstActiveUser(
                AutoWonderContext.get().getUserId(), "上传平台品牌标志");
        return Result.ok(brandingService.uploadLogo(AutoWonderContext.get().getUserId(), file));
    }

    @GetMapping("/logo")
    public ResponseEntity<byte[]> logo() {
        byte[] bytes;
        try {
            bytes = brandingService.logoBytes();
        } catch (ObjectStorageException e) {
            LOGGER.error("logo OSS read failed: {}", e.describe(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(brandingService.logoContentType()))
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(bytes);
    }
}
