package com.aliyun.autowonder.statemachine;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.statemachine.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class StatusTemplateService {

    private final StatusTemplateDao templateDao;
    private final StatusNodeDao nodeDao;
    private final StatusTransitionDao transitionDao;

    public StatusTemplateService(StatusTemplateDao templateDao, StatusNodeDao nodeDao,
                                 StatusTransitionDao transitionDao) {
        this.templateDao = templateDao;
        this.nodeDao = nodeDao;
        this.transitionDao = transitionDao;
    }

    // --- Template ---

    public List<TemplateVO> listTemplates(long tenantId, String workType) {
        List<TemplateVO> result = new ArrayList<>();
        for (StatusTemplateDO t : templateDao.listByWorkType(tenantId, workType)) {
            result.add(toTemplateVO(t));
        }
        return result;
    }

    public TemplateDetailVO getTemplateDetail(long templateId) {
        StatusTemplateDO t = templateDao.findById(templateId);
        if (t == null) {
            throw new BizException(ErrorCode.STATUS_TEMPLATE_NOT_FOUND);
        }
        TemplateDetailVO vo = new TemplateDetailVO();
        vo.setId(t.getId());
        vo.setWorkType(t.getWorkType());
        vo.setName(t.getName());
        vo.setIsDefault(t.getIsDefault() != null && t.getIsDefault() == 1);
        vo.setGmtCreate(t.getGmtCreate());
        vo.setGmtModified(t.getGmtModified());
        vo.setNodes(listNodes(templateId));
        vo.setTransitions(listTransitions(templateId));
        return vo;
    }

    public TemplateVO createTemplate(CreateTemplateRequest req, long tenantId, long userId) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.STATUS_TEMPLATE_NAME_REQUIRED);
        }
        if (req.getWorkType() == null || req.getWorkType().isBlank()) {
            throw new BizException(ErrorCode.STATUS_TEMPLATE_WORK_TYPE_REQUIRED);
        }
        StatusTemplateDO t = new StatusTemplateDO();
        t.setTenantId(tenantId);
        t.setWorkType(req.getWorkType().trim());
        t.setName(req.getName().trim());
        t.setIsDefault(0);
        t.setCreatorId(userId);
        templateDao.insert(t);
        return toTemplateVO(t);
    }

    @Transactional
    public TemplateVO updateTemplate(long templateId, UpdateTemplateRequest req, long tenantId, long userId) {
        StatusTemplateDO t = templateDao.findById(templateId);
        if (t == null) {
            throw new BizException(ErrorCode.STATUS_TEMPLATE_NOT_FOUND);
        }
        String name = (req.getName() != null) ? req.getName().trim() : t.getName();
        Integer isDefault = (req.getIsDefault() != null && req.getIsDefault()) ? 1 : t.getIsDefault();
        if (req.getIsDefault() != null && req.getIsDefault()) {
            templateDao.clearDefault(tenantId, t.getWorkType());
            isDefault = 1;
        }
        int rows = templateDao.update(templateId, tenantId, name, isDefault, t.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.STATUS_TEMPLATE_VERSION_CONFLICT);
        }
        return toTemplateVO(templateDao.findById(templateId));
    }

    @Transactional
    public void deleteTemplate(long templateId, long tenantId) {
        StatusTemplateDO t = templateDao.findById(templateId);
        if (t == null) {
            throw new BizException(ErrorCode.STATUS_TEMPLATE_NOT_FOUND);
        }
        List<StatusNodeDO> nodes = nodeDao.listByTemplateId(templateId);
        for (StatusNodeDO node : nodes) {
            if (nodeDao.countWorkitemsUsingNode(node.getId()) > 0) {
                throw new BizException(ErrorCode.STATUS_TEMPLATE_DELETE_IN_USE);
            }
        }
        transitionDao.deleteByTemplateId(templateId);
        for (StatusNodeDO node : nodes) {
            nodeDao.deleteById(node.getId());
        }
        templateDao.softDelete(templateId, tenantId, t.getVersion());
    }

    // --- Nodes ---

    public List<NodeVO> listNodes(long templateId) {
        List<NodeVO> result = new ArrayList<>();
        for (StatusNodeDO n : nodeDao.listByTemplateId(templateId)) {
            result.add(toNodeVO(n));
        }
        return result;
    }

    public NodeVO createNode(long templateId, CreateNodeRequest req, long tenantId) {
        StatusTemplateDO t = templateDao.findById(templateId);
        if (t == null) {
            throw new BizException(ErrorCode.STATUS_TEMPLATE_NOT_FOUND);
        }
        if (req.getCode() == null || req.getCode().isBlank()) {
            throw new BizException(ErrorCode.STATUS_NODE_CODE_REQUIRED);
        }
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.STATUS_NODE_NAME_REQUIRED);
        }
        if (req.getCategory() == null || req.getCategory().isBlank()) {
            throw new BizException(ErrorCode.STATUS_NODE_CATEGORY_REQUIRED);
        }
        if (nodeDao.findByTemplateAndCode(templateId, req.getCode().trim()) != null) {
            throw new BizException(ErrorCode.STATUS_NODE_CODE_DUPLICATE);
        }
        StatusNodeDO n = new StatusNodeDO();
        n.setTenantId(tenantId);
        n.setTemplateId(templateId);
        n.setCode(req.getCode().trim());
        n.setName(req.getName().trim());
        n.setCategory(req.getCategory().trim());
        n.setSort(req.getSort() != null ? req.getSort() : 0);
        nodeDao.insert(n);
        return toNodeVO(n);
    }

    public NodeVO updateNode(long nodeId, UpdateNodeRequest req) {
        StatusNodeDO n = nodeDao.findById(nodeId);
        if (n == null) {
            throw new BizException(ErrorCode.STATUS_NODE_NOT_FOUND);
        }
        String code = (req.getCode() != null) ? req.getCode().trim() : n.getCode();
        String name = (req.getName() != null) ? req.getName().trim() : n.getName();
        String category = (req.getCategory() != null) ? req.getCategory().trim() : n.getCategory();
        Integer sort = (req.getSort() != null) ? req.getSort() : n.getSort();
        if (!code.equals(n.getCode())) {
            if (nodeDao.findByTemplateAndCode(n.getTemplateId(), code) != null) {
                throw new BizException(ErrorCode.STATUS_NODE_CODE_DUPLICATE);
            }
        }
        nodeDao.update(nodeId, code, name, category, sort);
        return toNodeVO(nodeDao.findById(nodeId));
    }

    public void deleteNode(long nodeId) {
        StatusNodeDO n = nodeDao.findById(nodeId);
        if (n == null) {
            throw new BizException(ErrorCode.STATUS_NODE_NOT_FOUND);
        }
        if (nodeDao.countWorkitemsUsingNode(nodeId) > 0) {
            throw new BizException(ErrorCode.STATUS_NODE_DELETE_IN_USE);
        }
        nodeDao.deleteById(nodeId);
    }

    // --- Transitions ---

    public List<TransitionVO> listTransitions(long templateId) {
        List<TransitionVO> result = new ArrayList<>();
        for (StatusTransitionDO tr : transitionDao.listByTemplateId(templateId)) {
            result.add(toTransitionVO(tr));
        }
        return result;
    }

    public TransitionVO createTransition(long templateId, CreateTransitionRequest req, long tenantId) {
        StatusTemplateDO t = templateDao.findById(templateId);
        if (t == null) {
            throw new BizException(ErrorCode.STATUS_TEMPLATE_NOT_FOUND);
        }
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.STATUS_TRANSITION_NAME_REQUIRED);
        }
        if (transitionDao.findByTemplateFromTo(templateId, req.getFromNodeId(), req.getToNodeId()) != null) {
            throw new BizException(ErrorCode.STATUS_TRANSITION_DUPLICATE);
        }
        StatusTransitionDO tr = new StatusTransitionDO();
        tr.setTenantId(tenantId);
        tr.setTemplateId(templateId);
        tr.setFromNodeId(req.getFromNodeId());
        tr.setToNodeId(req.getToNodeId());
        tr.setName(req.getName().trim());
        transitionDao.insert(tr);
        return toTransitionVO(tr);
    }

    public TransitionVO updateTransition(long transitionId, UpdateTransitionRequest req) {
        StatusTransitionDO tr = transitionDao.findById(transitionId);
        if (tr == null) {
            throw new BizException(ErrorCode.STATUS_TRANSITION_NOT_FOUND);
        }
        Long fromNodeId = (req.getFromNodeId() != null) ? req.getFromNodeId() : tr.getFromNodeId();
        Long toNodeId = (req.getToNodeId() != null) ? req.getToNodeId() : tr.getToNodeId();
        String name = (req.getName() != null) ? req.getName().trim() : tr.getName();
        transitionDao.update(transitionId, fromNodeId, toNodeId, name);
        return toTransitionVO(transitionDao.findById(transitionId));
    }

    public void deleteTransition(long transitionId) {
        StatusTransitionDO tr = transitionDao.findById(transitionId);
        if (tr == null) {
            throw new BizException(ErrorCode.STATUS_TRANSITION_NOT_FOUND);
        }
        transitionDao.deleteById(transitionId);
    }

    // --- Converters ---

    private TemplateVO toTemplateVO(StatusTemplateDO t) {
        TemplateVO vo = new TemplateVO();
        vo.setId(t.getId());
        vo.setWorkType(t.getWorkType());
        vo.setName(t.getName());
        vo.setIsDefault(t.getIsDefault() != null && t.getIsDefault() == 1);
        vo.setGmtCreate(t.getGmtCreate());
        vo.setGmtModified(t.getGmtModified());
        return vo;
    }

    private NodeVO toNodeVO(StatusNodeDO n) {
        NodeVO vo = new NodeVO();
        vo.setId(n.getId());
        vo.setTemplateId(n.getTemplateId());
        vo.setCode(n.getCode());
        vo.setName(n.getName());
        vo.setCategory(n.getCategory());
        vo.setSort(n.getSort());
        vo.setGmtCreate(n.getGmtCreate());
        return vo;
    }

    private TransitionVO toTransitionVO(StatusTransitionDO tr) {
        TransitionVO vo = new TransitionVO();
        vo.setId(tr.getId());
        vo.setTemplateId(tr.getTemplateId());
        vo.setFromNodeId(tr.getFromNodeId());
        vo.setToNodeId(tr.getToNodeId());
        vo.setName(tr.getName());
        vo.setGmtCreate(tr.getGmtCreate());
        return vo;
    }
}
