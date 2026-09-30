package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.common.error.BizException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 * 对外免登录只读分享入口（AuthFilter 按令牌形态放行）。令牌不可猜（256-bit 随机），
 * 内容仅含经 expose 工具显式暴露的产物；浏览器使用沙箱预览，原始 HTML 一律以附件下载。
 */
@RestController
public class ShareController {

    private final ExternalArtifactShareService shareService;
    private final ArtifactShareRequestDao requests;

    public ShareController(ExternalArtifactShareService shareService, ArtifactShareRequestDao requests) {
        this.shareService = shareService;
        this.requests = requests;
    }

    @GetMapping(value = "/api/share/workitems/{token}/requests/{requestId}", params = {"!metadata", "!preview"})
    public ResponseEntity<byte[]> request(@PathVariable("token") String token,
                                           @PathVariable("requestId") long requestId,
                                           @RequestParam(value = "download", defaultValue = "false") boolean download,
                                           @RequestHeader(value = HttpHeaders.ACCEPT, defaultValue = "") String accept) {
        var workitem = shareService.findWorkitemByShareToken(token);
        if (workitem == null) return notFoundBytes();
        var request = requests.findById(workitem.getTenantId(), workitem.getId(), requestId);
        if (request == null) return notFoundBytes();
        if (("PENDING".equals(request.getStatus()) || "SHARED".equals(request.getStatus()))
                && !download && accept.contains("text/html")) {
            return previewPage(token, "requests", requestId);
        }
        if ("PENDING".equals(request.getStatus())) {
            return ResponseEntity.status(202)
                    .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.RETRY_AFTER, "5")
                    .body(("<!doctype html><html lang=\"zh\"><meta charset=\"utf-8\">"
                            + "<meta http-equiv=\"refresh\" content=\"5\"><title>产物生成中</title>"
                            + "<p>产物正在生成，执行成功并校验通过后可读取。此页面会自动刷新。</p></html>")
                            .getBytes(StandardCharsets.UTF_8));
        }
        if (!"SHARED".equals(request.getStatus()) || request.getArtifactId() == null) return notFoundBytes();
        return artifact(token, request.getArtifactId(), download, accept);
    }

    /** Metadata uses the same capability and exposure checks as the bytes; never include storage references. */
    @GetMapping(value = "/api/share/workitems/{token}/{kind:requests|artifacts}/{id}", params = "metadata")
    public ResponseEntity<PreviewMetadata> metadata(@PathVariable String token, @PathVariable String kind,
                                                   @PathVariable long id) {
        var workitem = shareService.findWorkitemByShareToken(token);
        if (workitem == null) return ResponseEntity.notFound().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        if ("requests".equals(kind)) {
            var request = requests.findById(workitem.getTenantId(), workitem.getId(), id);
            if (request == null) return ResponseEntity.notFound().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
            if ("PENDING".equals(request.getStatus())) {
                return ResponseEntity.status(202).header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header(HttpHeaders.RETRY_AFTER, "5")
                        .body(new PreviewMetadata("PENDING", request.getName(), null));
            }
            if (!"SHARED".equals(request.getStatus()) || request.getArtifactId() == null)
                return ResponseEntity.notFound().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
            id = request.getArtifactId();
        }
        ArtifactDO artifact = shareService.findExposedArtifact(workitem.getTenantId(), workitem.getId(), id);
        if (artifact == null) return ResponseEntity.notFound().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new PreviewMetadata("SHARED", ExternalArtifactShareService.logicalName(artifact.getName()), artifact.getSize()));
    }

    public record PreviewMetadata(String status, String name, Long size) {}

    /** Keep the public shell on the existing capability URL; page routes require BUC SSO internally. */
    @GetMapping(value = "/api/share/workitems/{token}/{kind:requests|artifacts}/{id}", params = {"preview", "!metadata"})
    public ResponseEntity<Resource> preview(@PathVariable String token, @PathVariable String kind,
                                            @PathVariable long id) {
        if (!metadata(token, kind, id).getStatusCode().is2xxSuccessful())
            return ResponseEntity.notFound().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .body(new ClassPathResource("static/index.html"));
    }

    private ResponseEntity<byte[]> previewPage(String token, String kind, long id) {
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION,
                        "/api/share/workitems/" + token + "/" + kind + "/" + id + "?preview")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.VARY, HttpHeaders.ACCEPT)
                .header("Referrer-Policy", "no-referrer").build();
    }

    @GetMapping("/api/share/workitems/{token}")
    public ResponseEntity<String> directory(@PathVariable("token") String token) {
        try {
            var workitem = shareService.findWorkitemByShareToken(token);
            if (workitem == null) {
                return notFound();
            }
            List<ArtifactDO> artifacts = shareService.listExposed(workitem.getTenantId(), workitem.getId());
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(renderDirectory(workitem.getTitle(), artifacts, token));
        } catch (BizException ex) {
            return notFound();
        }
    }

    @GetMapping(value = "/api/share/workitems/{token}/artifacts/{artifactId}", params = {"!metadata", "!preview"})
    public ResponseEntity<byte[]> artifact(@PathVariable("token") String token,
                                           @PathVariable("artifactId") long artifactId,
                                           @RequestParam(value = "download", required = false,
                                                   defaultValue = "false") boolean download,
                                           @RequestHeader(value = HttpHeaders.ACCEPT, defaultValue = "") String accept) {
        try {
            var workitem = shareService.findWorkitemByShareToken(token);
            if (workitem == null) {
                return notFoundBytes();
            }
            ArtifactDO artifact = shareService.findExposedArtifact(
                    workitem.getTenantId(), workitem.getId(), artifactId);
            if (artifact == null) {
                return notFoundBytes();
            }
            if (!download && accept.contains("text/html")) return previewPage(token, "artifacts", artifactId);
            byte[] bytes = shareService.loadContent(artifact);
            String fileName = ExternalArtifactShareService.logicalName(artifact.getName());
            // 文件名里的路径分隔符跨浏览器保存行为不一，统一压平
            String downloadName = fileName.replace('/', '_');
            MediaType contentType = contentType(fileName);
            boolean inline = !download && isInlineType(fileName);
            ContentDisposition disposition = inline
                    ? ContentDisposition.inline().build()
                    : ContentDisposition.attachment().filename(downloadName, StandardCharsets.UTF_8).build();
            return ResponseEntity.ok()
                    .contentType(contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.VARY, HttpHeaders.ACCEPT)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(bytes);
        } catch (BizException ex) {
            return notFoundBytes();
        }
    }

    private String renderDirectory(String title, List<ArtifactDO> artifacts, String token) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(HtmlUtils.htmlEscape(title)).append(" · AutoWonder</title></head>")
                .append("<body style=\"font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;")
                .append("max-width:860px;margin:32px auto;padding:0 16px;color:#24292f\">")
                .append("<h2 style=\"margin-bottom:4px\">").append(HtmlUtils.htmlEscape(title)).append("</h2>")
                .append("<p style=\"color:#57606a;font-size:13px\">AutoWonder 对外只读产物（")
                .append(artifacts.size()).append("）· 仅含已授权公开的结论文件</p>");
        if (artifacts.isEmpty()) {
            html.append("<p>暂无对外公开的产物。</p></body></html>");
            return html.toString();
        }
        html.append("<table style=\"border-collapse:collapse;width:100%;font-size:14px\">")
                .append("<tr style=\"text-align:left;color:#57606a\">")
                .append("<th style=\"padding:8px;border-bottom:1px solid #d0d7de\">文件</th>")
                .append("<th style=\"padding:8px;border-bottom:1px solid #d0d7de\">类型</th>")
                .append("<th style=\"padding:8px;border-bottom:1px solid #d0d7de\">大小</th>")
                .append("<th style=\"padding:8px;border-bottom:1px solid #d0d7de\">发布时间</th></tr>");
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (ArtifactDO artifact : artifacts) {
            String name = ExternalArtifactShareService.logicalName(artifact.getName());
            String url = "/api/share/workitems/" + HtmlUtils.htmlEscape(token)
                    + "/artifacts/" + artifact.getId();
            html.append("<tr><td style=\"padding:8px;border-bottom:1px solid #d8dee4\">")
                    .append("<a href=\"").append(url).append("\">")
                    .append(HtmlUtils.htmlEscape(name)).append("</a>")
                    .append(" <a style=\"font-size:12px;color:#57606a\" href=\"").append(url)
                    .append("?download=1\">下载</a></td>")
                    .append("<td style=\"padding:8px;border-bottom:1px solid #d8dee4\">")
                    .append(HtmlUtils.htmlEscape(artifact.getType() == null ? "" : artifact.getType()))
                    .append("</td><td style=\"padding:8px;border-bottom:1px solid #d8dee4\">")
                    .append(readableSize(artifact.getSize()))
                    .append("</td><td style=\"padding:8px;border-bottom:1px solid #d8dee4\">")
                    .append(artifact.getGmtCreate() == null ? "" : dateFormat.format(artifact.getGmtCreate()))
                    .append("</td></tr>");
        }
        html.append("</table></body></html>");
        return html.toString();
    }

    private static String readableSize(Long bytes) {
        if (bytes == null) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        for (String unit : new String[]{"KB", "MB", "GB"}) {
            value /= 1024;
            if (value < 1024) {
                return String.format("%.1f %s", value, unit);
            }
        }
        return String.format("%.1f TB", value / 1024);
    }

    private MediaType contentType(String name) {
        switch (extension(name)) {
            case "md":
            case "markdown":
                return MediaType.valueOf("text/markdown;charset=UTF-8");
            case "txt":
            case "log":
                return MediaType.valueOf("text/plain;charset=UTF-8");
            case "json":
                return MediaType.APPLICATION_JSON;
            case "jsonl":
                return MediaType.valueOf("application/x-ndjson;charset=UTF-8");
            case "csv":
                return MediaType.valueOf("text/csv;charset=UTF-8");
            case "png":
                return MediaType.IMAGE_PNG;
            case "jpg":
            case "jpeg":
                return MediaType.IMAGE_JPEG;
            case "gif":
                return MediaType.IMAGE_GIF;
            case "webp":
                return MediaType.valueOf("image/webp");
            case "mp4":
            case "m4v":
                return MediaType.valueOf("video/mp4");
            case "webm":
                return MediaType.valueOf("video/webm");
            case "ogg":
            case "ogv":
                return MediaType.valueOf("video/ogg");
            case "mov":
                return MediaType.valueOf("video/quicktime");
            default:
                return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private boolean isInlineType(String name) {
        return !MediaType.APPLICATION_OCTET_STREAM.equals(contentType(name));
    }

    private String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private ResponseEntity<String> notFound() {
        return ResponseEntity.status(404)
                    .contentType(MediaType.valueOf("text/plain;charset=UTF-8"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body("链接无效或产物已下线");
    }

    private ResponseEntity<byte[]> notFoundBytes() {
        return ResponseEntity.status(404)
                .contentType(MediaType.valueOf("text/plain;charset=UTF-8"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body("链接无效或产物已下线".getBytes(StandardCharsets.UTF_8));
    }
}
