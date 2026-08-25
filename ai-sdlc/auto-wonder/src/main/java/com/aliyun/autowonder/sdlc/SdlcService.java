package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import com.aliyun.autowonder.sdlc.dto.*;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SdlcService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final SdlcDao sdlcDao;
    private final SdlcStepDao stepDao;
    private final StatusNodeDao statusNodeDao;
    private final WorkitemDao workitemDao;
    private final AgentVersionDao agentVersionDao;
    private final AgentDao agentDao;

    public SdlcService(SdlcDao sdlcDao, SdlcStepDao stepDao,
                       StatusNodeDao statusNodeDao, WorkitemDao workitemDao,
                       AgentVersionDao agentVersionDao, AgentDao agentDao) {
        this.sdlcDao = sdlcDao;
        this.stepDao = stepDao;
        this.statusNodeDao = statusNodeDao;
        this.workitemDao = workitemDao;
        this.agentVersionDao = agentVersionDao;
        this.agentDao = agentDao;
    }

    @Transactional
    public SdlcVO create(CreateSdlcRequest req, long tenantId, long userId) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.SDLC_NAME_REQUIRED);
        }
        SdlcDO s = new SdlcDO();
        s.setTenantId(tenantId);
        s.setName(req.getName().trim());
        s.setDescription(req.getDescription());
        s.setWorkType(req.getWorkType());
        s.setStatus("DRAFT");
        s.setIsDefault(0);
        s.setCreatorId(userId);
        s.setVersion(0);
        sdlcDao.insert(s);
        return toVO(s, List.of());
    }

    public SdlcVO get(long id) {
        SdlcDO s = sdlcDao.findById(id);
        if (s == null) {
            throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        }
        List<SdlcStepDO> steps = stepDao.listBySdlc(id);
        return toVO(s, steps);
    }

    public List<SdlcVO> list(String workType, String status, int page, int size) {
        int p = page < 1 ? 1 : page;
        int sz = Math.min(size < 1 ? 20 : size, 100);
        int offset = (p - 1) * sz;
        List<SdlcVO> result = new ArrayList<>();
        for (SdlcDO s : sdlcDao.list(workType, status, offset, sz)) {
            result.add(toVO(s, null));
        }
        return result;
    }

    @Transactional
    public SdlcVO update(long id, UpdateSdlcRequest req, long tenantId, long userId) {
        SdlcDO s = sdlcDao.findById(id);
        if (s == null) {
            throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        }
        requireEditable(s);
        int rows = sdlcDao.update(id, tenantId,
                req.getName() != null ? req.getName().trim() : s.getName(),
                req.getDescription() != null ? req.getDescription() : s.getDescription(),
                req.getWorkType() != null ? req.getWorkType() : s.getWorkType(),
                s.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.SDLC_VERSION_CONFLICT);
        }
        return get(id);
    }

    @Transactional
    public void delete(long id, long tenantId, long userId) {
        SdlcDO s = sdlcDao.findById(id);
        if (s == null) {
            throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        }
        long workitemCount = workitemDao.countBySdlcId(id);
        List<Long> agentIds = agentVersionDao.listAgentIdsBySdlcId(id);
        if (workitemCount > 0 || !agentIds.isEmpty()) {
            List<Long> workitemIds = workitemCount > 0
                    ? workitemDao.listIdsBySdlcId(id, 5) : List.of();
            throw new BizException(ErrorCode.SDLC_DELETE_IN_USE,
                    buildInUseMessage(workitemCount, workitemIds, agentIds));
        }
        int rows = sdlcDao.softDelete(id, tenantId, s.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.SDLC_VERSION_CONFLICT);
        }
        stepDao.deleteAllBySdlc(id, tenantId);
    }

    private String buildInUseMessage(long workitemCount, List<Long> workitemIds, List<Long> agentIds) {
        List<String> refs = new ArrayList<>();
        if (workitemCount > 0) {
            StringBuilder part = new StringBuilder("工单 ").append(workitemCount).append(" 个");
            if (!workitemIds.isEmpty()) {
                part.append("(");
                for (int i = 0; i < workitemIds.size(); i++) {
                    if (i > 0) {
                        part.append(", ");
                    }
                    part.append("#").append(workitemIds.get(i));
                }
                if (workitemCount > workitemIds.size()) {
                    part.append(" 等");
                }
                part.append(")");
            }
            refs.add(part.toString());
        }
        if (!agentIds.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Long agentId : agentIds) {
                AgentDO agent = agentDao.findById(agentId);
                if (agent != null && agent.getName() != null && !agent.getName().isBlank()) {
                    names.add(agent.getName() + "(ID:" + agentId + ")");
                } else {
                    names.add("ID:" + agentId);
                }
            }
            refs.add("数字员工 " + agentIds.size() + " 个(" + String.join(", ", names) + ")");
        }
        return ErrorCode.SDLC_DELETE_IN_USE.getMessage()
                + ": 引用源: " + String.join("; ", refs) + "。请先解除上述引用后再删除。";
    }

    @Transactional
    public StepVO addStep(long sdlcId, CreateStepRequest req, long tenantId, long userId) {
        SdlcDO s = sdlcDao.findById(sdlcId);
        if (s == null) {
            throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        }
        requireEditable(s);
        String checklistJson = normalizeJson(req.getChecklistJson());
        String gatePolicyJson = normalizeJson(req.getGatePolicyJson());
        requireValidJson("checklistJson", checklistJson);
        requireValidJson("gatePolicyJson", gatePolicyJson);
        SdlcStepDO step = new SdlcStepDO();
        step.setTenantId(tenantId);
        step.setSdlcId(sdlcId);
        Integer requestedOrder = req.getStepOrder();
        step.setStepOrder(requestedOrder != null && requestedOrder > 0
                ? requestedOrder : nextStepOrder(sdlcId));
        step.setName(req.getName());
        step.setKind(req.getKind());
        step.setInstructionMd(req.getInstructionMd());
        step.setChecklistJson(checklistJson);
        step.setGatePolicyJson(gatePolicyJson);
        step.setRequired(req.getRequired() == null ? Boolean.TRUE : req.getRequired());
        step.setTimeoutSeconds(req.getTimeoutSeconds());
        step.setRetryBudget(req.getRetryBudget());
        step.setCode(req.getCode());
        step.setHandlerType(req.getHandlerType());
        step.setHandlerRoleRef(req.getHandlerRoleRef());
        step.setStatusOnEnterCode(req.getStatusOnEnterCode());
        step.setOnSuccess(req.getOnSuccess());
        step.setOnFail(req.getOnFail());
        step.setCreatorId(userId);
        insertStepWithOrderRetry(step, sdlcId);
        return toStepVO(step);
    }

    /**
     * 唯一键冲突（并发新增或历史软删除占位）时重新计算序号重试，避免直接暴露系统内部错误。
     */
    private void insertStepWithOrderRetry(SdlcStepDO step, long sdlcId) {
        int maxAttempts = 3;
        for (int attempt = 1; ; attempt++) {
            try {
                stepDao.insert(step);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt >= maxAttempts) {
                    throw new BizException(ErrorCode.SDLC_STEP_ORDER_DUPLICATE);
                }
                step.setStepOrder(nextStepOrder(sdlcId));
            }
        }
    }

    @Transactional
    public StepVO updateStep(long sdlcId, long stepId, UpdateStepRequest req, long tenantId, long userId) {
        SdlcDO s = sdlcDao.findById(sdlcId);
        if (s == null) {
            throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        }
        requireContentEditable(s);
        SdlcStepDO step = stepDao.findById(stepId);
        if (step == null || step.getSdlcId() != sdlcId) {
            throw new BizException(ErrorCode.SDLC_STEP_NOT_FOUND);
        }
        String name = req.getName() != null ? req.getName() : step.getName();
        String kind = req.getKind() != null ? req.getKind() : step.getKind();
        String instructionMd = req.getInstructionMd() != null ? req.getInstructionMd() : step.getInstructionMd();
        String checklistJson = normalizeJson(req.getChecklistJson() != null ? req.getChecklistJson() : step.getChecklistJson());
        String gatePolicyJson = normalizeJson(req.getGatePolicyJson() != null ? req.getGatePolicyJson() : step.getGatePolicyJson());
        requireValidJson("checklistJson", checklistJson);
        requireValidJson("gatePolicyJson", gatePolicyJson);
        Boolean required = req.getRequired() != null ? req.getRequired() : step.getRequired();
        // timeoutSeconds/retryBudget 支持显式 null 恢复未配置，请求体未携带时保持原值
        Integer timeoutSeconds = req.isTimeoutSecondsPresent() ? req.getTimeoutSeconds() : step.getTimeoutSeconds();
        Integer retryBudget = req.isRetryBudgetPresent() ? req.getRetryBudget() : step.getRetryBudget();
        String code = req.getCode() != null ? req.getCode() : step.getCode();
        String handlerType = req.getHandlerType() != null ? req.getHandlerType() : step.getHandlerType();
        String handlerRoleRef = req.getHandlerRoleRef();
        String statusOnEnterCode = req.getStatusOnEnterCode();
        String onSuccess = req.getOnSuccess();
        String onFail = req.getOnFail();
        stepDao.update(stepId, tenantId, name, kind, instructionMd, checklistJson, gatePolicyJson,
                required, timeoutSeconds, retryBudget, code, handlerType, handlerRoleRef,
                statusOnEnterCode, onSuccess, onFail, userId);
        SdlcStepDO updated = stepDao.findById(stepId);
        return toStepVO(updated != null ? updated : step);
    }

    @Transactional
    public void deleteStep(long sdlcId, long stepId, long tenantId, long userId) {
        SdlcDO s = sdlcDao.findById(sdlcId);
        if (s == null) {
            throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        }
        requireEditable(s);
        SdlcStepDO step = stepDao.findById(stepId);
        if (step == null || step.getSdlcId() != sdlcId) {
            throw new BizException(ErrorCode.SDLC_STEP_NOT_FOUND);
        }
        stepDao.softDelete(stepId, tenantId, userId);
        renumberSteps(sdlcId, tenantId, userId);
    }

    @Transactional
    public void reorderSteps(long sdlcId, ReorderRequest req, long tenantId, long userId) {
        SdlcDO s = sdlcDao.findById(sdlcId);
        if (s == null) {
            throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        }
        requireEditable(s);
        List<Long> ids = req.getStepIds();
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<Long> validIds = new HashSet<>();
        for (SdlcStepDO st : stepDao.listBySdlc(sdlcId)) {
            validIds.add(st.getId());
        }
        if (!validIds.equals(new HashSet<>(ids))) {
            throw new BizException(ErrorCode.SDLC_STEP_NOT_FOUND);
        }
        // 两段式更新：先移到互不冲突的负数临时区，再赋目标序号，避免中间态唯一键冲突
        for (int i = 0; i < ids.size(); i++) {
            stepDao.updateOrder(ids.get(i), tenantId, Integer.MIN_VALUE + i, userId);
        }
        for (int i = 0; i < ids.size(); i++) {
            stepDao.updateOrder(ids.get(i), tenantId, i + 1, userId);
        }
    }

    @Transactional
    public SdlcVO enable(long id, Long statusTemplateId, long tenantId, long userId) {
        SdlcDO s = sdlcDao.findById(id);
        if (s == null) {
            throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        }
        if ("ENABLED".equals(s.getStatus())) {
            throw new BizException(ErrorCode.SDLC_ALREADY_ENABLED);
        }
        List<SdlcStepDO> steps = stepDao.listBySdlc(id);
        if (steps.isEmpty()) {
            throw new BizException(ErrorCode.SDLC_ENABLE_NO_STEPS);
        }
        validateStepOrder(steps);
        Long entryStepId = steps.get(0).getId();
        int rows = sdlcDao.updateStatus(id, tenantId, "ENABLED", entryStepId, s.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.SDLC_VERSION_CONFLICT);
        }
        return get(id);
    }

    @Transactional
    public void disable(long id, long tenantId, long userId) {
        SdlcDO s = sdlcDao.findById(id);
        if (s == null) {
            throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        }
        if (!"ENABLED".equals(s.getStatus())) {
            throw new BizException(ErrorCode.SDLC_NOT_ENABLED);
        }
        int rows = sdlcDao.updateStatus(id, tenantId, "DISABLED", null, s.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.SDLC_VERSION_CONFLICT);
        }
    }

    private void validateStepOrder(List<SdlcStepDO> steps) {
        if (steps.get(0).getStepOrder() != 1) {
            throw new BizException(ErrorCode.SDLC_ENABLE_STEP_ORDER_GAP);
        }
        for (int i = 0; i < steps.size() - 1; i++) {
            if (steps.get(i + 1).getStepOrder() - steps.get(i).getStepOrder() != 1) {
                throw new BizException(ErrorCode.SDLC_ENABLE_STEP_ORDER_GAP);
            }
        }
    }

    private void requireEditable(SdlcDO sdlc) {
        if (!"DRAFT".equals(sdlc.getStatus())
                && !"DISABLED".equals(sdlc.getStatus())
                && !"ENABLED".equals(sdlc.getStatus())) {
            throw new BizException(ErrorCode.SDLC_NOT_DRAFT);
        }
    }

    // 步骤内容级编辑（instructionMd/checklistJson/gatePolicyJson 等）不改变流程结构，
    // 相比 requireEditable 额外放行 ACTIVE 流程，与结构变更（增删步骤、调整顺序）区分开。
    private void requireContentEditable(SdlcDO sdlc) {
        if (!"DRAFT".equals(sdlc.getStatus())
                && !"DISABLED".equals(sdlc.getStatus())
                && !"ENABLED".equals(sdlc.getStatus())
                && !"ACTIVE".equals(sdlc.getStatus())) {
            throw new BizException(ErrorCode.SDLC_NOT_DRAFT);
        }
    }

    // MySQL JSON 列不接受空串写入（ERROR 3140），空白输入归一化为 null，
    // 既实现「清空字段」语义，又避免 DataIntegrityViolation 被映射为误导性 409。
    private String normalizeJson(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // sdlc_step 的 checklist_json/gate_policy_json 是 MySQL JSON 列，
    // 非法 JSON 写入会触发 DataIntegrityViolation 并被映射为误导性的 409「数据冲突」，
    // 因此在写库前做语法校验并以参数错误（400）快速失败。
    private void requireValidJson(String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            JSON_MAPPER.readTree(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + " 不是合法的 JSON");
        }
    }

    private void renumberSteps(long sdlcId, long tenantId, long userId) {
        List<SdlcStepDO> remaining = stepDao.listBySdlc(sdlcId);
        for (int i = 0; i < remaining.size(); i++) {
            if (remaining.get(i).getStepOrder() != i + 1) {
                stepDao.updateOrder(remaining.get(i).getId(), tenantId, i + 1, userId);
            }
        }
    }

    private int nextStepOrder(long sdlcId) {
        List<SdlcStepDO> steps = stepDao.listBySdlc(sdlcId);
        int max = 0;
        for (SdlcStepDO st : steps) {
            if (st.getStepOrder() != null && st.getStepOrder() > max) {
                max = st.getStepOrder();
            }
        }
        return max + 1;
    }

    SdlcVO toVO(SdlcDO s, List<SdlcStepDO> steps) {
        SdlcVO vo = new SdlcVO();
        vo.setId(s.getId());
        vo.setName(s.getName());
        vo.setDescription(s.getDescription());
        vo.setWorkType(s.getWorkType());
        vo.setStatus(s.getStatus());
        vo.setIsDefault(s.getIsDefault());
        vo.setEntryStepId(s.getEntryStepId());
        vo.setVersion(s.getVersion());
        vo.setGmtCreate(s.getGmtCreate());
        if (steps != null) {
            List<StepVO> svos = new ArrayList<>();
            for (SdlcStepDO st : steps) {
                svos.add(toStepVO(st));
            }
            vo.setSteps(svos);
        }
        return vo;
    }

    StepVO toStepVO(SdlcStepDO st) {
        StepVO v = new StepVO();
        v.setId(st.getId());
        v.setSdlcId(st.getSdlcId());
        v.setStepOrder(st.getStepOrder());
        v.setName(st.getName());
        v.setKind(st.getKind());
        v.setInstructionMd(st.getInstructionMd());
        v.setChecklistJson(st.getChecklistJson());
        v.setGatePolicyJson(st.getGatePolicyJson());
        v.setRequired(st.getRequired() == null ? Boolean.TRUE : st.getRequired());
        v.setTimeoutSeconds(st.getTimeoutSeconds());
        v.setRetryBudget(st.getRetryBudget());
        v.setCode(st.getCode());
        v.setHandlerType(st.getHandlerType());
        v.setHandlerRoleRef(st.getHandlerRoleRef());
        v.setStatusOnEnterCode(st.getStatusOnEnterCode());
        v.setOnSuccess(st.getOnSuccess());
        v.setOnFail(st.getOnFail());
        return v;
    }
}
