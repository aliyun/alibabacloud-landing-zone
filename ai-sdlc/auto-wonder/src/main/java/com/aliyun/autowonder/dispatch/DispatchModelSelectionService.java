package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.executor.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Objects;

@Service
public class DispatchModelSelectionService {
    private static final Logger log = LoggerFactory.getLogger(DispatchModelSelectionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DispatchModelSelectionDao selections;
    private final DispatchDao dispatches;
    private final ExecutorDao executors;
    private final ExecutorLaunchOptionsService options;

    public DispatchModelSelectionService(DispatchModelSelectionDao selections, DispatchDao dispatches,
            ExecutorDao executors, ExecutorLaunchOptionsService options) {
        this.selections = selections; this.dispatches = dispatches; this.executors = executors; this.options = options;
    }

    public DispatchModelSelection freeze(DispatchDO dispatch) {
        try {
            String saved = selections.find(dispatch.getTenantId(), dispatch.getId());
            if (saved != null) return JSON.readValue(saved, DispatchModelSelection.class);
            ExecutorDO executor = executors.findById(dispatch.getExecutorId());
            if (executor == null || !Objects.equals(executor.getTenantId(), dispatch.getTenantId()))
                throw new IllegalStateException("Model selection executor is unavailable");
            var config = ExecutorLaunchConfigService.parse(executor.getLaunchConfig());
            List<DispatchDO> history = dispatch.executionSourceType() == ExecutionSourceType.WORKITEM
                    ? dispatches.listByWorkitem(dispatch.getTenantId(), dispatch.getWorkitemId())
                    : dispatches.listBySource(dispatch.getTenantId(), dispatch.executionSourceType().name(), dispatch.getWorkitemId());
            int count = entryCount(dispatch, history);
            DispatchModelSelection decision = decide(config, count,
                    executor.getConfigVersion() == null ? 1 : executor.getConfigVersion(),
                    isInteraction(dispatch));
            if (decision.getModel() != null) {
                String provider = ExecutorLaunchOptionsService.resolveProvider(executor.getClientKind());
                if (ExecutorLaunchOptionsService.isQoderFamily(provider)
                        && options.models(provider).stream().noneMatch(m -> decision.getModel().equals(m.getValue()))) {
                    decision.setModel(null); decision.setThreshold(null); decision.setReasonCode("MODEL_UNAVAILABLE");
                }
            }
            selections.insert(dispatch.getTenantId(), dispatch.getId(), JSON.writeValueAsString(decision));
            DispatchModelSelection frozen = JSON.readValue(selections.find(dispatch.getTenantId(), dispatch.getId()), DispatchModelSelection.class);
            log.info("task model selection dispatchId={} agentId={} entryCount={} model={} threshold={} policyVersion={} reason={}",
                    dispatch.getId(), dispatch.getAgentId(), frozen.getEntryCount(), frozen.getModel(),
                    frozen.getThreshold(), frozen.getPolicyVersion(), frozen.getReasonCode());
            return frozen;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Invalid persisted task model selection", error);
        }
    }

    private static boolean isInteraction(DispatchDO row) {
        return java.util.Set.of("COMMENT_INTERACTION", "SIDE_INTERACTION", "CANONICAL_INTERACTION").contains(
                row.getResumeMode() == null ? "" : row.getResumeMode());
    }

    static int entryCount(DispatchDO current, List<DispatchDO> history) {
        int handoffs = 0; DispatchDO first = null;
        if (history != null) for (DispatchDO row : history) {
            if (row.getId() > current.getId() || !Objects.equals(row.getAgentId(), current.getAgentId())
                    || isInteraction(row)) continue;
            if (first == null || row.getId() < first.getId()) first = row;
            String key = row.getIdempotencyKey();
            if (key != null && key.startsWith("handoff:")) handoffs++;

        }
        boolean initial = first != null && (first.getIdempotencyKey() == null || !first.getIdempotencyKey().startsWith("handoff:"));
        return Math.max(1, handoffs + (initial ? 1 : 0));
    }

    static DispatchModelSelection decide(ExecutorLaunchConfigService.LaunchConfig config, int count,
            int version, boolean interaction) {
        DispatchModelSelection selection = new DispatchModelSelection();
        selection.setDefaultModel(config.model); selection.setEntryCount(count); selection.setPolicyVersion(version);
        ModelUpgradePolicy policy = config.modelUpgradePolicy;
        selection.setReasonCode(interaction ? "INTERACTION" : policy == null || !policy.isEnabled() ? "POLICY_DISABLED" : "THRESHOLD_NOT_REACHED");
        if (!interaction && count >= 2 && policy != null && policy.isEnabled() && policy.getLevels() != null) {
            for (ModelUpgradePolicy.Level level : policy.getLevels()) {
                if (count >= level.getEntryCount()) {
                    selection.setModel(level.getModel()); selection.setThreshold(level.getEntryCount());
                    selection.setReasonCode("ENTRY_THRESHOLD_REACHED");
                }
            }
        }
        return selection;
    }
}
