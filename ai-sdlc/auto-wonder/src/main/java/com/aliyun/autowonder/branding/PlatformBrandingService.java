package com.aliyun.autowonder.branding;

import com.aliyun.autowonder.branding.dto.LogoUploadVO;
import com.aliyun.autowonder.branding.dto.PlatformBrandingVO;
import com.aliyun.autowonder.branding.dto.UpdatePlatformBrandingRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Map;
import java.util.Set;

@Service
public class PlatformBrandingService {

    public static final String DEFAULT_PLATFORM_NAME = "AutoWonder";
    public static final String DEFAULT_THEME_KEY = "aliyun-orange";
    public static final String DEFAULT_PRIMARY_COLOR = "#f97316";
    public static final String DEFAULT_DEPLOYMENT_VERSION = "x.x.x";

    private static final Set<String> THEME_KEYS = Set.of(
            "aliyun-orange", "ocean-blue", "jade-green", "indigo", "rose",
            "cyan", "amber", "violet", "graphite", "teal");
    private static final Map<String, String> LOGO_EXTENSIONS = Map.of(
            "image/png", ".png",
            "image/jpeg", ".jpg",
            "image/webp", ".webp");
    private static final long MAX_LOGO_SIZE = 2L * 1024L * 1024L;
    private static final long LOGO_CACHE_TTL_MS = 5 * 60 * 1000L;

    private volatile LogoCacheEntry logoCache;

    private final PlatformBrandingDao brandingDao;
    private final ObjectStorage objectStorage;
    private final String bucket;
    private final String publicBaseUrl;
    private final String trustedMcpBaseUrl;
    private final String recommendedRuntimeVersion;
    private final String deploymentVersion;
    private final boolean communityEdition;

    public PlatformBrandingService(PlatformBrandingDao brandingDao,
                                   ObjectStorage objectStorage,
                                   OssProperties ossProperties,
                                   @Value("${autowonder.public-base-url:}") String publicBaseUrl,
                                   @Value("${autowonder.runtime.recommended-version:0.2.150}") String recommendedRuntimeVersion,
                                   @Value("${autowonder.version:x.x.x}") String deploymentVersion,
                                   @Value("${autowonder.community-edition:false}") boolean communityEdition) {
        this.brandingDao = brandingDao;
        this.objectStorage = objectStorage;
        this.bucket = ossProperties.resolveArtifactBucket();
        this.publicBaseUrl = requirePublicBaseUrl(publicBaseUrl);
        this.trustedMcpBaseUrl = this.publicBaseUrl + "/api/mcp";
        this.recommendedRuntimeVersion = requireRuntimeVersion(recommendedRuntimeVersion);
        this.deploymentVersion = normalizeDeploymentVersion(deploymentVersion);
        this.communityEdition = communityEdition;
    }

    public PlatformBrandingVO publicConfig() {
        return toVO(currentOrDefault(), false);
    }

    public String trustedPublicBaseUrl() {
        return publicBaseUrl;
    }

    public String recommendedRuntimeVersion() {
        return recommendedRuntimeVersion;
    }

    public PlatformBrandingVO adminConfig(boolean canManage) {
        return toVO(currentOrDefault(), canManage);
    }

    public PlatformBrandingVO update(Long userId, UpdatePlatformBrandingRequest request) {
        PlatformBrandingDO next = new PlatformBrandingDO();
        next.setPlatformName(nonBlank(request.getPlatformName(), "平台名称不能为空", 128));
        next.setThemeKey(validateThemeKey(request.getThemeKey()));
        next.setPrimaryColor(validatePrimaryColor(request.getPrimaryColor()));
        next.setDomain(normalizeOptionalUrl(request.getDomain(), "域名格式不合法"));
        next.setModifierId(userId);
        int updated = brandingDao.update(next);
        if (updated == 0) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return toVO(currentOrDefault(), true);
    }

    public LogoUploadVO uploadLogo(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "Logo 文件不能为空");
        }
        if (file.getSize() > MAX_LOGO_SIZE) {
            throw new BizException(ErrorCode.PARAM_INVALID, "Logo 文件不能超过 2MB");
        }
        String contentType = normalizeContentType(file.getContentType());
        String ext = LOGO_EXTENSIONS.get(contentType);
        if (ext == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "Logo 仅支持 PNG、JPG、WebP");
        }
        try {
            String key = "platform/branding/logo-" + System.currentTimeMillis() + ext;
            StoredObject stored = objectStorage.put(bucket, key, file.getBytes());
            int updated = brandingDao.updateLogo(stored.getOssRef(), contentType, userId);
            if (updated == 0) {
                throw new BizException(ErrorCode.NOT_FOUND);
            }
            logoCache = null;
            PlatformBrandingDO current = currentOrDefault();
            return new LogoUploadVO(logoUrl(current));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "Logo 上传失败");
        }
    }

    public byte[] logoBytes() {
        PlatformBrandingDO current = currentOrDefault();
        if (current.getLogoOssRef() == null || current.getLogoOssRef().isBlank()) {
            return null;
        }
        LogoCacheEntry entry = ensureLogoLoaded(current);
        return entry.bytes;
    }

    public String logoContentType() {
        PlatformBrandingDO current = currentOrDefault();
        if (current.getLogoOssRef() == null || current.getLogoOssRef().isBlank()) {
            return "application/octet-stream";
        }
        LogoCacheEntry entry = ensureLogoLoaded(current);
        return entry.contentType;
    }

    private LogoCacheEntry ensureLogoLoaded(PlatformBrandingDO current) {
        String ossRef = current.getLogoOssRef();
        LogoCacheEntry cached = logoCache;
        if (cached != null && cached.ossRef.equals(ossRef)
                && cached.expiresAt > System.currentTimeMillis()) {
            return cached;
        }
        byte[] bytes = objectStorage.get(ossRef);
        String contentType = current.getLogoContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        LogoCacheEntry entry = new LogoCacheEntry(
                ossRef, bytes, contentType,
                System.currentTimeMillis() + LOGO_CACHE_TTL_MS);
        logoCache = entry;
        return entry;
    }

    private static final class LogoCacheEntry {
        final String ossRef;
        final byte[] bytes;
        final String contentType;
        final long expiresAt;

        LogoCacheEntry(String ossRef, byte[] bytes, String contentType, long expiresAt) {
            this.ossRef = ossRef;
            this.bytes = bytes;
            this.contentType = contentType;
            this.expiresAt = expiresAt;
        }
    }

    private PlatformBrandingDO currentOrDefault() {
        PlatformBrandingDO current = brandingDao.findActive();
        if (current != null) {
            return current;
        }
        PlatformBrandingDO fallback = new PlatformBrandingDO();
        fallback.setPlatformName(DEFAULT_PLATFORM_NAME);
        fallback.setThemeKey(DEFAULT_THEME_KEY);
        fallback.setPrimaryColor(DEFAULT_PRIMARY_COLOR);
        // An unconfigured domain stays empty so callers fall back to autowonder.public-base-url.
        fallback.setDomain(null);
        return fallback;
    }

    private PlatformBrandingVO toVO(PlatformBrandingDO current, boolean canManage) {
        return new PlatformBrandingVO(
                safe(current.getPlatformName(), DEFAULT_PLATFORM_NAME),
                logoUrl(current),
                safe(current.getThemeKey(), DEFAULT_THEME_KEY),
                safe(current.getPrimaryColor(), DEFAULT_PRIMARY_COLOR),
                current.getDomain(),
                trustedMcpBaseUrl,
                recommendedRuntimeVersion,
                deploymentVersion,
                communityEdition,
                canManage);
    }

    private String logoUrl(PlatformBrandingDO current) {
        if (current.getLogoOssRef() == null || current.getLogoOssRef().isBlank()) {
            return "/logo.png";
        }
        int version = current.getVersion() == null ? 0 : current.getVersion();
        return "/api/platform/branding/logo?v=" + version;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String nonBlank(String value, String message, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            throw new BizException(ErrorCode.PARAM_INVALID, message);
        }
        return trimmed;
    }

    private static String validateThemeKey(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!THEME_KEYS.contains(trimmed)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "主题配色不合法");
        }
        return trimmed;
    }

    private static String validatePrimaryColor(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.matches("^#[0-9a-fA-F]{6}$")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "主题颜色格式不合法");
        }
        return trimmed.toLowerCase();
    }

    private static String requireRuntimeVersion(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.matches("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$")) {
            throw new IllegalStateException("autowonder.runtime.recommended-version must be a semantic version");
        }
        return trimmed;
    }

    private static String normalizeDeploymentVersion(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_DEPLOYMENT_VERSION;
        }
        if (DEFAULT_DEPLOYMENT_VERSION.equals(trimmed)
                || trimmed.matches("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$")) {
            return trimmed;
        }
        throw new IllegalStateException("autowonder.version must be x.x.x or a semantic version");
    }

    private static String requirePublicBaseUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("autowonder.public-base-url must be configured");
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null
                    || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return trimmed.replaceAll("/+$", "");
        } catch (Exception e) {
            throw new IllegalStateException("autowonder.public-base-url must be an absolute http(s) URL", e);
        }
    }

    private static String normalizeOptionalUrl(String value, String message) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException();
            }
            return trimmed.replaceAll("/+$", "");
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID, message);
        }
    }

    private static String normalizeContentType(String value) {
        if (value == null) {
            return "";
        }
        int semicolon = value.indexOf(';');
        return (semicolon >= 0 ? value.substring(0, semicolon) : value).trim().toLowerCase();
    }
}
