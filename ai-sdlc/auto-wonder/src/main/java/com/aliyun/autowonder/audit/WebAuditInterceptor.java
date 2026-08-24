package com.aliyun.autowonder.audit;

import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebAuditInterceptor implements HandlerInterceptor {

    private static final Pattern FIRST_ID = Pattern.compile("/(\\d+)(?:/|$)");

    private final AuditLogService auditLogService;

    public WebAuditInterceptor(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        if (!shouldAudit(request)) {
            return;
        }
        AutoWonderContext ctx = AutoWonderContext.get();
        Long tenantId = ctx.getCurrentWorkspaceId();
        Long userId = ctx.getUserId();
        if (tenantId == null || userId == null) {
            return;
        }
        String path = request.getRequestURI();
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(tenantId);
        record.setActorId(userId);
        record.setActorType("HUMAN");
        record.setModule(moduleOf(path));
        record.setAction(actionOf(request.getMethod(), path));
        record.setTargetType(targetTypeOf(path));
        record.setTargetId(firstId(path));
        record.setTriggerType("ACTIVE");
        record.setTriggerSource("USER_CLICK");
        record.setEventType("http." + request.getMethod().toLowerCase(Locale.ROOT));
        record.detail("path", path)
                .detail("method", request.getMethod())
                .detail("status", response.getStatus())
                .detail("success", response.getStatus() < 400)
                .detail("query", request.getQueryString())
                .detail("error", ex != null ? ex.getClass().getSimpleName() : null);
        auditLogService.record(record);
    }

    private boolean shouldAudit(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return false;
        }
        return path != null && path.startsWith("/api/")
                && !path.startsWith("/api/auth/")
                && !path.startsWith("/api/audit-logs")
                && !path.startsWith("/api/daemon/");
    }

    private String moduleOf(String path) {
        String type = targetTypeOf(path);
        return type == null ? "API" : type.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private String targetTypeOf(String path) {
        if (path == null || !path.startsWith("/api/")) {
            return null;
        }
        String rest = path.substring("/api/".length());
        int slash = rest.indexOf('/');
        String segment = slash >= 0 ? rest.substring(0, slash) : rest;
        return switch (segment) {
            case "workitems" -> "workitem";
            case "agents" -> "agent";
            case "skills" -> "skill";
            case "status-templates" -> "status-template";
            default -> segment;
        };
    }

    private Long firstId(String path) {
        Matcher matcher = FIRST_ID.matcher(path);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String actionOf(String method, String path) {
        String normalized = path == null ? "" : path.toUpperCase(Locale.ROOT)
                .replace("/API/", "")
                .replaceAll("/\\d+", "/{ID}")
                .replace('/', '_')
                .replaceAll("[^A-Z0-9_]+", "_")
                .replaceAll("_+", "_");
        String prefix = switch (method.toUpperCase(Locale.ROOT)) {
            case "POST" -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> method.toUpperCase(Locale.ROOT);
        };
        return normalized.isBlank() ? prefix : prefix + "_" + normalized;
    }
}
