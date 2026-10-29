package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.user.UserAccountStates;
import com.aliyun.autowonder.user.UserDao;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Narrow persistence boundary for execution-control timeline events.  The event row is the
 * immutable request/intent record; dispatch_recovery.control_event_id only points at it.
 */
@Component
public class WorkitemControlEventWriter {

    public static final String EXECUTION_CONTROL = WorkitemEventType.EXECUTION_CONTROL.code();
    public static final String DELIVERY_CONTROL = WorkitemEventType.DELIVERY_CONTROL.code();

    private final WorkitemEventDao eventDao;
    private AgentDao agentDao;
    private UserDao userDao;

    public WorkitemControlEventWriter(WorkitemEventDao eventDao) {
        this.eventDao = eventDao;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setNameResolvers(AgentDao agentDao, UserDao userDao) {
        this.agentDao = agentDao;
        this.userDao = userDao;
    }

    private void fillNames(ExecutionControlEvent event) {
        if (event.getAgentName() == null && event.getAgentId() != null && agentDao != null) {
            AgentDO agent = agentDao.findById(event.getAgentId());
            if (agent != null) event.setAgentName(agent.getName());
        }
        if (event.getRequestedByName() == null && event.getRequestedById() != null
                && event.getRequestedById() > 0 && userDao != null) {
            event.setRequestedByName(UserAccountStates.displayName(userDao.findByIdIncludingDeleted(event.getRequestedById())));
        }
    }

    public long recordExecution(long tenantId, long workitemId, ExecutionControlEvent event) {
        fillNames(event);
        WorkitemEventDO row = new WorkitemEventDO();
        row.setTenantId(tenantId);
        row.setWorkitemId(workitemId);
        row.setEventType(EXECUTION_CONTROL);
        row.setFromVal(event.getDispatchId() == null ? null : String.valueOf(event.getDispatchId()));
        row.setToVal(event.getTargetDispatchId() == null ? null : String.valueOf(event.getTargetDispatchId()));
        row.setActorType(event.getActorType());
        row.setActorRef(event.getActorRef());
        row.setDetailJson(event.toDetailJson().toJSONString());
        eventDao.insert(row);
        return row.getId();
    }

    public long recordDelivery(long tenantId, long workitemId, DeliveryControlEvent event) {
        if (event.getRequestedByName() == null && event.getRequestedById() != null
                && event.getRequestedById() > 0 && userDao != null) {
            event.setRequestedByName(UserAccountStates.displayName(userDao.findByIdIncludingDeleted(event.getRequestedById())));
        }
        WorkitemEventDO row = new WorkitemEventDO();
        row.setTenantId(tenantId);
        row.setWorkitemId(workitemId);
        row.setEventType(DELIVERY_CONTROL);
        row.setActorType(event.getActorType());
        row.setActorRef(event.getActorRef());
        row.setDetailJson(event.toDetailJson().toJSONString());
        eventDao.insert(row);
        return row.getId();
    }

    /** Returns the immutable request/intent event referenced by the recovery pointer. */
    public Optional<ExecutionControlEvent> findExecution(long tenantId, long eventId) {
        if (eventId <= 0) return Optional.empty();
        WorkitemEventDO row = eventDao.findById(tenantId, eventId);
        if (row == null || !EXECUTION_CONTROL.equals(row.getEventType())) return Optional.empty();
        return Optional.of(ExecutionControlEvent.fromDetailJson(row.getDetailJson()));
    }

    /** Resolves either an execution intent or a delivery-close intent for stop confirmation. */
    public Optional<ControlIntent> findIntent(long tenantId, long eventId) {
        if (eventId <= 0) return Optional.empty();
        WorkitemEventDO row = eventDao.findById(tenantId, eventId);
        if (row == null) return Optional.empty();
        if (DELIVERY_CONTROL.equals(row.getEventType())) {
            JSONObject detail = row.getDetailJson() == null ? new JSONObject() : JSONObject.parseObject(row.getDetailJson());
            return Optional.of(new ControlIntent(detail.getString("action"), "CONFIRMED", "DELIVERY_CLOSE",
                    null, detail.getLong("requestedById"), detail.getString("requestedByName"), null, false));
        }
        if (!EXECUTION_CONTROL.equals(row.getEventType())) return Optional.empty();
        ExecutionControlEvent event = ExecutionControlEvent.fromDetailJson(row.getDetailJson());
        return Optional.of(new ControlIntent(event.getAction(), event.getPhase(), event.getOrigin(),
                event.getAgentName(), event.getRequestedById(), event.getRequestedByName(),
                event.getStopPending(), Boolean.TRUE.equals(event.getForced())));
    }

    public record ControlIntent(String action, String phase, String origin, String agentName,
                                Long requestedById, String requestedByName, Boolean stopPending,
                                boolean forced) {
    }

    public static final class ExecutionControlEvent {
        private String action;
        private String phase;
        private String origin;
        private Long dispatchId;
        private Long agentId;
        private String agentName;
        private Long requestedById;
        private String requestedByName;
        private Long sourceDispatchId;
        private Long targetDispatchId;
        private Long supersedesRequestEventId;
        private Long requestEventId;
        private Long parentRequestEventId;
        private Boolean stopPending;
        private Boolean forced;
        private String reason;
        private String mode;
        private String actorType;
        private Long actorRef;

        public String getAction() { return action; }
        public ExecutionControlEvent setAction(String action) { this.action = action; return this; }
        public String getPhase() { return phase; }
        public ExecutionControlEvent setPhase(String phase) { this.phase = phase; return this; }
        public String getOrigin() { return origin; }
        public ExecutionControlEvent setOrigin(String origin) { this.origin = origin; return this; }
        public Long getDispatchId() { return dispatchId; }
        public ExecutionControlEvent setDispatchId(Long dispatchId) { this.dispatchId = dispatchId; return this; }
        public Long getAgentId() { return agentId; }
        public ExecutionControlEvent setAgentId(Long agentId) { this.agentId = agentId; return this; }
        public String getAgentName() { return agentName; }
        public ExecutionControlEvent setAgentName(String agentName) { this.agentName = agentName; return this; }
        public Long getRequestedById() { return requestedById; }
        public ExecutionControlEvent setRequestedById(Long requestedById) { this.requestedById = requestedById; return this; }
        public String getRequestedByName() { return requestedByName; }
        public ExecutionControlEvent setRequestedByName(String requestedByName) { this.requestedByName = requestedByName; return this; }
        public Long getSourceDispatchId() { return sourceDispatchId; }
        public ExecutionControlEvent setSourceDispatchId(Long sourceDispatchId) { this.sourceDispatchId = sourceDispatchId; return this; }
        public Long getTargetDispatchId() { return targetDispatchId; }
        public ExecutionControlEvent setTargetDispatchId(Long targetDispatchId) { this.targetDispatchId = targetDispatchId; return this; }
        public Long getSupersedesRequestEventId() { return supersedesRequestEventId; }
        public ExecutionControlEvent setSupersedesRequestEventId(Long value) { this.supersedesRequestEventId = value; return this; }
        public Long getRequestEventId() { return requestEventId; }
        public ExecutionControlEvent setRequestEventId(Long requestEventId) { this.requestEventId = requestEventId; return this; }
        public Long getParentRequestEventId() { return parentRequestEventId; }
        public ExecutionControlEvent setParentRequestEventId(Long value) { this.parentRequestEventId = value; return this; }
        public Boolean getStopPending() { return stopPending; }
        public ExecutionControlEvent setStopPending(Boolean stopPending) { this.stopPending = stopPending; return this; }
        public Boolean getForced() { return forced; }
        public ExecutionControlEvent setForced(Boolean forced) { this.forced = forced; return this; }
        public String getReason() { return reason; }
        public ExecutionControlEvent setReason(String reason) { this.reason = reason; return this; }
        public String getMode() { return mode; }
        public ExecutionControlEvent setMode(String mode) { this.mode = mode; return this; }
        public String getActorType() { return actorType; }
        public ExecutionControlEvent setActorType(String actorType) { this.actorType = actorType; return this; }
        public Long getActorRef() { return actorRef; }
        public ExecutionControlEvent setActorRef(Long actorRef) { this.actorRef = actorRef; return this; }

        public JSONObject toDetailJson() {
            JSONObject detail = new JSONObject();
            detail.put("action", action);
            detail.put("phase", phase);
            detail.put("origin", origin);
            detail.put("dispatchId", dispatchId);
            detail.put("agentId", agentId);
            detail.put("agentName", agentName);
            detail.put("requestedById", requestedById);
            detail.put("requestedByName", requestedByName);
            detail.put("sourceDispatchId", sourceDispatchId);
            detail.put("targetDispatchId", targetDispatchId);
            detail.put("supersedesRequestEventId", supersedesRequestEventId);
            detail.put("requestEventId", requestEventId);
            detail.put("parentRequestEventId", parentRequestEventId);
            detail.put("stopPending", stopPending);
            detail.put("forced", forced);
            detail.put("reason", reason);
            detail.put("mode", mode);
            return detail;
        }

        public static ExecutionControlEvent fromDetailJson(String detailJson) {
            JSONObject detail = detailJson == null ? new JSONObject() : JSONObject.parseObject(detailJson);
            return new ExecutionControlEvent()
                    .setAction(detail.getString("action"))
                    .setPhase(detail.getString("phase"))
                    .setOrigin(detail.getString("origin"))
                    .setDispatchId(detail.getLong("dispatchId"))
                    .setAgentId(detail.getLong("agentId"))
                    .setAgentName(detail.getString("agentName"))
                    .setRequestedById(detail.getLong("requestedById"))
                    .setRequestedByName(detail.getString("requestedByName"))
                    .setSourceDispatchId(detail.getLong("sourceDispatchId"))
                    .setTargetDispatchId(detail.getLong("targetDispatchId"))
                    .setSupersedesRequestEventId(detail.getLong("supersedesRequestEventId"))
                    .setRequestEventId(detail.getLong("requestEventId"))
                    .setParentRequestEventId(detail.getLong("parentRequestEventId"))
                    .setStopPending(detail.getBoolean("stopPending"))
                    .setForced(detail.getBoolean("forced"))
                    .setReason(detail.getString("reason"))
                    .setMode(detail.getString("mode"));
        }
    }

    public static final class DeliveryControlEvent {
        private String action;
        private Long requestedById;
        private String requestedByName;
        private String actorType;
        private Long actorRef;
        private String reason;
        private final List<AffectedDispatch> affectedDispatches = new ArrayList<>();

        public String getAction() { return action; }
        public DeliveryControlEvent setAction(String action) { this.action = action; return this; }
        public Long getRequestedById() { return requestedById; }
        public DeliveryControlEvent setRequestedById(Long requestedById) { this.requestedById = requestedById; return this; }
        public String getRequestedByName() { return requestedByName; }
        public DeliveryControlEvent setRequestedByName(String requestedByName) { this.requestedByName = requestedByName; return this; }
        public String getActorType() { return actorType; }
        public DeliveryControlEvent setActorType(String actorType) { this.actorType = actorType; return this; }
        public Long getActorRef() { return actorRef; }
        public DeliveryControlEvent setActorRef(Long actorRef) { this.actorRef = actorRef; return this; }
        public String getReason() { return reason; }
        public DeliveryControlEvent setReason(String reason) { this.reason = reason; return this; }
        public List<AffectedDispatch> getAffectedDispatches() { return affectedDispatches; }
        public DeliveryControlEvent addAffected(AffectedDispatch affected) { this.affectedDispatches.add(affected); return this; }

        public JSONObject toDetailJson() {
            JSONObject detail = new JSONObject();
            detail.put("action", action);
            detail.put("origin", "DELIVERY_CLOSE");
            detail.put("requestedById", requestedById);
            detail.put("requestedByName", requestedByName);
            detail.put("reason", reason);
            List<JSONObject> rows = new ArrayList<>();
            for (AffectedDispatch affected : affectedDispatches) {
                JSONObject row = new JSONObject();
                row.put("dispatchId", affected.dispatchId());
                row.put("fromStatus", affected.fromStatus());
                row.put("toStatus", affected.toStatus());
                row.put("stopPending", affected.stopPending());
                rows.add(row);
            }
            detail.put("affectedDispatches", rows);
            return detail;
        }
    }

    public record AffectedDispatch(Long dispatchId, String fromStatus, String toStatus, Boolean stopPending) {
    }
}
