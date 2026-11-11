package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Single read-only domain gate for starting a new execution.
 *
 * <p>Only the two management states defined by the execution-boundary spec are rejected:
 * an OFFLINE agent or a DISABLED SDLC. Missing, deleted or cross-tenant resources keep
 * their pre-existing rejection semantics, so callers must not rely on this gate to
 * authorize anything else. Double unavailability reports the agent first.
 *
 * <p>This check is deliberately read-only and never takes a row lock. The authoritative
 * linearization point is {@link DispatchAdmissionService#admit}; this gate only rejects
 * early and avoids packaging work for executions that can never start.
 */
@Service
public class ExecutionAvailabilityService {

    public static final String AGENT_OFFLINE = "OFFLINE";
    public static final String SDLC_DISABLED = "DISABLED";

    private final AgentDao agentDao;
    private final SdlcDao sdlcDao;

    public ExecutionAvailabilityService(AgentDao agentDao, SdlcDao sdlcDao) {
        this.agentDao = agentDao;
        this.sdlcDao = sdlcDao;
    }

    /**
     * Rejects a new execution whose agent is OFFLINE or whose effective SDLC is DISABLED.
     *
     * @param tenantId tenant that owns the execution
     * @param agentId  agent that would execute the new dispatch
     * @param sdlcId   effective flow of the execution; {@code null} is a legal no-flow path
     */
    public void requireStartable(long tenantId, Long agentId, Long sdlcId) {
        if (agentId != null && agentId > 0) {
            AgentDO agent = agentDao.findById(agentId);
            if (agent != null && Objects.equals(Long.valueOf(tenantId), agent.getTenantId())
                    && AGENT_OFFLINE.equalsIgnoreCase(agent.getStatus())) {
                throw BizException.keyed(ErrorCode.EXECUTION_AGENT_OFFLINE, "execution.agent_offline",
                        new Object[]{agentId},
                        "agent is offline: agentId=" + agentId + ", tenantId=" + tenantId);
            }
        }
        if (sdlcId == null || sdlcId <= 0) {
            return;
        }
        SdlcDO sdlc = sdlcDao.findById(sdlcId);
        if (sdlc != null && Objects.equals(Long.valueOf(tenantId), sdlc.getTenantId())
                && SDLC_DISABLED.equalsIgnoreCase(sdlc.getStatus())) {
            throw BizException.keyed(ErrorCode.EXECUTION_SDLC_DISABLED, "execution.sdlc_disabled",
                    new Object[]{sdlcId},
                    "sdlc is disabled: sdlcId=" + sdlcId + ", tenantId=" + tenantId);
        }
    }

    /** Stable dispatch failure reason for a management-state rejection, or {@code null}. */
    public static String failureReason(BizException failure) {
        if (failure == null) {
            return null;
        }
        if (ErrorCode.EXECUTION_AGENT_OFFLINE.getCode().equals(failure.getCode())) {
            return DispatchFailureReason.AGENT_OFFLINE;
        }
        if (ErrorCode.EXECUTION_SDLC_DISABLED.getCode().equals(failure.getCode())) {
            return DispatchFailureReason.SDLC_DISABLED;
        }
        return null;
    }
}