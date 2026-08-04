package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import com.aliyun.autowonder.sdlc.dto.*;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SdlcService {

    private final SdlcDao sdlcDao;
    private final SdlcStepDao stepDao;
    private final StatusNodeDao statusNodeDao;
    private final WorkitemDao workitemDao;

    public SdlcService(SdlcDao sdlcDao, SdlcStepDao stepDao,
                       StatusNodeDao statusNodeDao, WorkitemDao workitemDao) {
        this.sdlcDao = sdlcDao;
        this.stepDao = stepDao;
        this.statusNodeDao = statusNodeDao;
        this.workitemDao = workitemDao;
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
        if (workitemDao.countBySdlcId(id) > 0) {
            throw new BizException(ErrorCode.SDLC_DELETE_IN_USE);
        }
        int rows = sdlcDao.softDelete(id, tenantId, s.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.SDLC_VERSION_CONFLICT);
        }
        stepDao.deleteAllBySdlc(id, tenantId);
    }

    @Transactional
    public StepVO addStep(long sdlcId, CreateStepRequest req, long tenantId, long userId) {
        SdlcDO s = sdlcDao.findById(sdlcId);
        if (s == null) {
            throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        }
        requireEditable(s);
        SdlcStepDO step = new SdlcStepDO();
        step.setTenantId(tenantId);
        step.setSdlcId(sdlcId);
        step.setStepOrder(req.getStepOrder());
        step.setName(req.getName());
        step.setKind(req.getKind());
        step.setInstructionMd(req.getInstructionMd());
        step.setChecklistJson(req.getChecklistJson());
        step.setGatePolicyJson(req.getGatePolicyJson());
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
        stepDao.insert(step);
        return toStepVO(step);
    }

    @Transactional
    public StepVO updateStep(long sdlcId, long stepId, UpdateStepRequest req, long tenantId, long userId) {
        SdlcDO s = sdlcDao.findById(sdlcId);
        if (s == null) {
            throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        }
        requireEditable(s);
        SdlcStepDO step = stepDao.findById(stepId);
        if (step == null || step.getSdlcId() != sdlcId) {
            throw new BizException(ErrorCode.SDLC_STEP_NOT_FOUND);
        }
        String name = req.getName() != null ? req.getName() : step.getName();
        String kind = req.getKind() != null ? req.getKind() : step.getKind();
        String instructionMd = req.getInstructionMd() != null ? req.getInstructionMd() : step.getInstructionMd();
        String checklistJson = req.getChecklistJson() != null ? req.getChecklistJson() : step.getChecklistJson();
        String gatePolicyJson = req.getGatePolicyJson() != null ? req.getGatePolicyJson() : step.getGatePolicyJson();
        Boolean required = req.getRequired() != null ? req.getRequired() : step.getRequired();
        Integer timeoutSeconds = req.getTimeoutSeconds() != null ? req.getTimeoutSeconds() : step.getTimeoutSeconds();
        Integer retryBudget = req.getRetryBudget() != null ? req.getRetryBudget() : step.getRetryBudget();
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
