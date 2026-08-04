package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class DispatchMcpTokenService {
    static final String PREFIX = "awdispatch_";
    private static final String PURPOSE = "dispatch-mcp";
    private static final long TTL_SECONDS = 24 * 60 * 60;
    private static final Set<String> ACTIVE = Set.of("PACKAGING", "PENDING", "DISPATCHED", "ACKED", "RUNNING", "PAUSING");

    private final JwtService jwtService;
    private final DispatchDao dispatchDao;
    private final WorkitemDao workitemDao;

    public DispatchMcpTokenService(JwtService jwtService, DispatchDao dispatchDao, WorkitemDao workitemDao) {
        this.jwtService = jwtService;
        this.dispatchDao = dispatchDao;
        this.workitemDao = workitemDao;
    }

    public String issue(DispatchDO dispatch) {
        long userId = dispatch.getCreatorId() == null ? 0L : dispatch.getCreatorId();
        if (userId <= 0 && workitemDao != null) {
            WorkitemDO workitem = workitemDao.findById(dispatch.getWorkitemId());
            if (workitem != null && dispatch.getTenantId().equals(workitem.getTenantId())) {
                if (workitem.getCreatorId() != null && workitem.getCreatorId() > 0) {
                    userId = workitem.getCreatorId();
                } else if (workitem.getAssignOperatorId() != null && workitem.getAssignOperatorId() > 0) {
                    userId = workitem.getAssignOperatorId();
                }
            }
        }
        if (userId <= 0) {
            throw new IllegalStateException("dispatch MCP principal is unavailable for dispatch " + dispatch.getId());
        }
        return PREFIX + jwtService.signScoped(userId, dispatch.getTenantId(), PURPOSE, dispatch.getId(), TTL_SECONDS);
    }

    public McpAccessTokenService.Principal authenticate(String token) {
        try {
            if (token == null || !token.startsWith(PREFIX)) {
                throw new IllegalArgumentException("invalid prefix");
            }
            Map<String, Object> claims = jwtService.parseScoped(token.substring(PREFIX.length()));
            if (!PURPOSE.equals(claims.get("purpose"))) {
                throw new IllegalArgumentException("invalid purpose");
            }
            long dispatchId = ((Number) claims.get("subjectId")).longValue();
            long tenantId = ((Number) claims.get("org")).longValue();
            DispatchDO dispatch = dispatchDao.findById(dispatchId);
            if (dispatch == null || !tenantIdEquals(dispatch, tenantId) || !ACTIVE.contains(dispatch.getStatus())) {
                throw new IllegalArgumentException("dispatch is inactive");
            }
            long userId = ((Number) claims.get("uid")).longValue();
            return new McpAccessTokenService.Principal(
                    tenantId, userId, -dispatchId, OrgAccessLevel.READ_WRITE,
                    McpAccessTokenService.CredentialType.DISPATCH);
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    private boolean tenantIdEquals(DispatchDO dispatch, long tenantId) {
        return dispatch.getTenantId() != null && dispatch.getTenantId() == tenantId;
    }
}
