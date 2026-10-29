package com.aliyun.autowonder.common.web;

import com.aliyun.autowonder.access.WorkspaceAccessDeniedException;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.WorkspaceDomainReadOnlyException;
import com.aliyun.autowonder.access.dto.WorkspaceAccessDeniedVO;
import com.aliyun.autowonder.common.error.AlreadyLoggedException;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.im.ImIdentityConflictException;
import com.aliyun.autowonder.im.ImIdentityConflictReason;
import com.aliyun.autowonder.im.dto.ImConflictWorkspaceView;
import com.aliyun.autowonder.im.dto.ImIdentityConflictView;
import com.aliyun.autowonder.workitem.WorkitemInOtherWorkspaceException;
import com.aliyun.autowonder.workitem.dto.WorkitemOwningWorkspaceVO;
import com.aliyun.autowonder.workspace.WorkspaceService;
import com.aliyun.autowonder.workspace.dto.WorkspaceVO;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final WorkspaceService workspaceService;

    public GlobalExceptionHandler() {
        this(null);
    }

    @Autowired
    public GlobalExceptionHandler(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * Identity conflicts carry a structured reason plus the Workspaces the current user may view.
     * Unreadable occupants are never named: they only set {@code restricted}.
     */
    @ExceptionHandler(ImIdentityConflictException.class)
    public ResponseEntity<Result<ImIdentityConflictView>> handleImIdentityConflict(
            ImIdentityConflictException ex) {
        ImIdentityConflictView view = new ImIdentityConflictView();
        view.setReason(ex.getReason().name());
        if (ex.getReason() == ImIdentityConflictReason.LEGACY_BINDING_OCCUPIED) {
            Map<Long, String> visible = visibleWorkspaces();
            List<ImConflictWorkspaceView> workspaces = new ArrayList<>();
            boolean restricted = false;
            Set<Long> occupants = new LinkedHashSet<>(ex.getOccupantTenantIds());
            for (Long occupantId : occupants) {
                String name = visible.get(occupantId);
                if (name == null) {
                    restricted = true;
                } else {
                    workspaces.add(new ImConflictWorkspaceView(occupantId, name));
                }
            }
            view.setWorkspaces(workspaces);
            view.setRestricted(restricted);
        }
        Result<ImIdentityConflictView> result = Result.failKeyed(ex.getCode(),
                ex.getMessageKey(), ex.getMessageArgs(), ex.getMessage(), view);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }

    private Map<Long, String> visibleWorkspaces() {
        Map<Long, String> result = new HashMap<>();
        if (workspaceService == null) {
            return result;
        }
        try {
            Long userId = AutoWonderContext.get().getUserId();
            if (userId == null) {
                return result;
            }
            for (WorkspaceVO workspace : workspaceService.listByUserWithAccess(userId)) {
                if (workspace.getId() != null && workspace.getName() != null) {
                    result.put(workspace.getId(), workspace.getName());
                }
            }
        } catch (RuntimeException ignored) {
            // Without a resolvable caller or readable membership nothing may be disclosed.
        }
        return result;
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException ex) {
        if (!(ex.getCause() instanceof AlreadyLoggedException)) {
            LOGGER.warn("biz exception: code={}, msg={}", ex.getCode(), ex.getMessage());
        }
        HttpStatus status = HttpStatus.OK;
        if (ErrorCode.UNAUTHORIZED.getCode().equals(ex.getCode())) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (ErrorCode.WORKSPACE_NOT_MEMBER.getCode().equals(ex.getCode())
                || ErrorCode.NO_PERMISSION.getCode().equals(ex.getCode())) {
            status = HttpStatus.FORBIDDEN;
        } else if (ErrorCode.PARAM_INVALID.getCode().equals(ex.getCode())
                || ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID.getCode().equals(ex.getCode())) {
            status = HttpStatus.BAD_REQUEST;
        } else if (ErrorCode.CONFLICT.getCode().equals(ex.getCode())
                || ErrorCode.WORKITEM_DELIVERY_CLOSED.getCode().equals(ex.getCode())
                || ErrorCode.WORKSPACE_OWNER_MUTATION_PROTECTED.getCode().equals(ex.getCode())
                || ErrorCode.WORKSPACE_SELF_LEVEL_MUTATION_FORBIDDEN.getCode().equals(ex.getCode())
                || ErrorCode.WORKSPACE_OWNER_TRANSFER_INVALID.getCode().equals(ex.getCode())) {
            status = HttpStatus.CONFLICT;
        }
        return ResponseEntity.status(status)
                .body(Result.fail(ex));
    }

    @ExceptionHandler(WorkspaceAccessDeniedException.class)
    public ResponseEntity<Result<WorkspaceAccessDeniedVO>> handleWorkspaceAccessDenied(
            WorkspaceAccessDeniedException ex) {
        WorkspaceAccessDeniedVO data = new WorkspaceAccessDeniedVO(
                ex.getCurrent(), ex.getRequired(), ex.getAction());
        Result<WorkspaceAccessDeniedVO> result = Result.failKeyed(
                ErrorCode.WORKSPACE_ACCESS_INSUFFICIENT.getCode(), "access.workspace_denied",
                new Object[]{SystemMessages.render(ex.getActionKey(), null, ex.getAction())}, ex.getMessage(), data);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
    }

    @ExceptionHandler(WorkspaceDomainReadOnlyException.class)
    public ResponseEntity<Result<Void>> handleWorkspaceDomainReadOnly(
            WorkspaceDomainReadOnlyException ex) {
        Result<Void> result = Result.failKeyed(
                ErrorCode.WORKSPACE_ACCESS_INSUFFICIENT.getCode(), "access.workspace_domain_read_only",
                new Object[]{SystemMessages.render(ex.getActionKey(), null, ex.getAction())}, ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
    }

    @ExceptionHandler(WorkitemInOtherWorkspaceException.class)
    public ResponseEntity<Result<WorkitemOwningWorkspaceVO>> handleWorkitemInOtherWorkspace(
            WorkitemInOtherWorkspaceException ex) {
        WorkitemOwningWorkspaceVO data = new WorkitemOwningWorkspaceVO();
        data.setWorkspaceId(ex.getWorkspaceId());
        data.setWorkspaceName(ex.getWorkspaceName());
        Result<WorkitemOwningWorkspaceVO> result = Result.failKeyed(
                ErrorCode.WORKITEM_IN_OTHER_WORKSPACE.getCode(),
                ErrorCode.WORKITEM_IN_OTHER_WORKSPACE.getMessageKey(),
                new Object[]{ex.getWorkspaceName()}, ex.getMessage(), data);
        return ResponseEntity.ok(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArg(IllegalArgumentException ex) {
        if (ex instanceof KeyedIllegalArgumentException keyed) {
            return Result.failKeyed(ErrorCode.PARAM_INVALID.getCode(), keyed.getMessageKey(),
                    keyed.getMessageArgs(), ex.getMessage(), null);
        }
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleMessageNotReadable(
            HttpMessageNotReadableException ex) {
        Throwable rootCause = rootCause(ex);
        ErrorCode errorCode = rootCause instanceof InvalidFormatException invalidFormat
                && WorkspaceAccessLevel.class.equals(invalidFormat.getTargetType())
                ? ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID
                : ErrorCode.PARAM_INVALID;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(errorCode));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String field = fieldError == null
                ? "request"
                : fieldError.getField();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failKeyed(ErrorCode.PARAM_INVALID.getCode(), "validation.field_invalid",
                        new Object[]{field}, field + " 参数不合法", null));
    }

    @ExceptionHandler(AlreadyLoggedException.class)
    public ResponseEntity<Result<Void>> handleAlreadyLogged(AlreadyLoggedException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.SYSTEM_ERROR));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        LOGGER.warn("method not supported method={} uri={} supported={} requestId={}",
                request.getMethod(), request.getRequestURI(), ex.getSupportedHttpMethods(),
                MDC.get("requestId"));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.failKeyed(ErrorCode.PARAM_INVALID.getCode(), "request.method_not_supported",
                        new Object[]{ex.getMethod()}, ex.getMessage(), null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        LOGGER.warn("data integrity violation: {}", cause != null ? cause.getMessage() : ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.failKeyed(ErrorCode.CONFLICT.getCode(), "error.conflict_retry",
                        null, "数据冲突，请刷新页面后重试", null));
    }

    @ExceptionHandler(Throwable.class)
    public Result<Void> handleThrowable(Throwable ex) {
        if (ex instanceof AlreadyLoggedException) {
            return Result.fail(ErrorCode.SYSTEM_ERROR);
        }
        LOGGER.error("unexpected exception", ex);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
