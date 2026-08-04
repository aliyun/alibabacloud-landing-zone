package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalStatusMappingDO;
import com.aliyun.autowonder.integration.common.ExternalStatusMappingDao;
import com.aliyun.autowonder.integration.provider.ExternalStatusOption;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemDetail;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.statemachine.StatusTemplateDO;
import com.aliyun.autowonder.statemachine.StatusTemplateDao;
import com.aliyun.autowonder.statemachine.StatusTransitionDO;
import com.aliyun.autowonder.statemachine.StatusTransitionDao;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ExternalStatusBootstrapService {

    private final StatusTemplateDao templateDao;
    private final StatusNodeDao nodeDao;
    private final StatusTransitionDao transitionDao;
    private final ExternalStatusMappingDao statusMappingDao;

    public ExternalStatusBootstrapService(StatusTemplateDao templateDao, StatusNodeDao nodeDao,
                                          StatusTransitionDao transitionDao, ExternalStatusMappingDao statusMappingDao) {
        this.templateDao = templateDao;
        this.nodeDao = nodeDao;
        this.transitionDao = transitionDao;
        this.statusMappingDao = statusMappingDao;
    }

    public StatusNodeDO ensureStatus(ExternalProjectBindingDO binding, ExternalWorkitemDetail detail,
                                     List<ExternalStatusOption> operationalStatuses, long userId) {
        StatusTemplateDO template = ensureTemplate(binding, detail.getWorkType(), userId);
        Map<String, ExternalStatusOption> statuses = orderedStatuses(detail, operationalStatuses);
        StatusNodeDO selected = null;
        int sort = 0;
        for (ExternalStatusOption status : statuses.values()) {
            StatusNodeDO node = ensureNode(binding, template, status, sort++);
            ensureMapping(binding, detail.getWorkType(), detail.getExternalIssueTypeId(), status, node);
            if (sameStatus(status.getName(), detail.getStatusName())) {
                selected = node;
            }
        }
        ensureTransitions(binding.getTenantId(), template.getId());
        if (selected != null) {
            return selected;
        }
        return nodeDao.findInitNode(template.getId());
    }

    public void ensureStatuses(ExternalProjectBindingDO binding, String workType, String externalIssueTypeId,
                               List<ExternalStatusOption> statuses, long userId) {
        StatusTemplateDO template = ensureTemplate(binding, workType, userId);
        int sort = 0;
        if (statuses != null) {
            for (ExternalStatusOption status : statuses) {
                if (status.getName() == null || status.getName().isBlank()) {
                    continue;
                }
                StatusNodeDO node = ensureNode(binding, template, status, sort++);
                ensureMapping(binding, workType, externalIssueTypeId, status, node);
            }
        }
        ensureTransitions(binding.getTenantId(), template.getId());
    }

    private StatusTemplateDO ensureTemplate(ExternalProjectBindingDO binding, String workType, long userId) {
        String name = templateName(binding, workType);
        for (StatusTemplateDO existing : templateDao.listByWorkType(binding.getTenantId(), workType)) {
            if (name.equals(existing.getName())) {
                return existing;
            }
        }
        StatusTemplateDO template = new StatusTemplateDO();
        template.setTenantId(binding.getTenantId());
        template.setWorkType(workType);
        template.setName(name);
        template.setIsDefault(0);
        template.setCreatorId(userId);
        template.setVersion(0);
        templateDao.insert(template);
        return template;
    }

    private StatusNodeDO ensureNode(ExternalProjectBindingDO binding, StatusTemplateDO template,
                                    ExternalStatusOption status, int sort) {
        String code = statusCode(status);
        StatusNodeDO existing = nodeDao.findByTemplateAndCode(template.getId(), code);
        if (existing != null) {
            return existing;
        }
        StatusNodeDO node = new StatusNodeDO();
        node.setTenantId(binding.getTenantId());
        node.setTemplateId(template.getId());
        node.setCode(code);
        node.setName(status.getName());
        node.setCategory(category(status.getName(), sort));
        node.setSort(sort);
        nodeDao.insert(node);
        return node;
    }

    private void ensureMapping(ExternalProjectBindingDO binding, String workType, ExternalStatusOption status, StatusNodeDO node) {
        ensureMapping(binding, workType, null, status, node);
    }

    private void ensureMapping(ExternalProjectBindingDO binding, String workType, String externalIssueTypeId,
                               ExternalStatusOption status, StatusNodeDO node) {
        ExternalStatusMappingDO existing = statusMappingDao.findByExternal(binding.getTenantId(), binding.getProvider(),
                binding.getId(), workType, status.getName());
        if (existing != null) {
            if (externalIssueTypeId != null && !externalIssueTypeId.isBlank()
                    && (existing.getExternalIssueTypeId() == null || existing.getExternalIssueTypeId().isBlank())) {
                statusMappingDao.updateExternalIssueType(existing.getId(), externalIssueTypeId);
            }
            return;
        }
        ExternalStatusMappingDO mapping = new ExternalStatusMappingDO();
        mapping.setTenantId(binding.getTenantId());
        mapping.setProvider(binding.getProvider());
        mapping.setBindingId(binding.getId());
        mapping.setExternalIssueTypeId(externalIssueTypeId);
        mapping.setExternalStatusId(status.getExternalId());
        mapping.setExternalStatusName(status.getName());
        mapping.setWorkType(workType);
        mapping.setStatusNodeId(node.getId());
        mapping.setEnabled(1);
        statusMappingDao.insert(mapping);
    }

    private void ensureTransitions(long tenantId, long templateId) {
        List<StatusNodeDO> nodes = nodeDao.listByTemplateId(templateId);
        Set<String> existing = new HashSet<>();
        for (StatusTransitionDO transition : transitionDao.listByTemplateId(templateId)) {
            existing.add(transition.getFromNodeId() + ":" + transition.getToNodeId());
        }
        for (StatusNodeDO from : nodes) {
            for (StatusNodeDO to : nodes) {
                if (from.getId().equals(to.getId())) {
                    continue;
                }
                String key = from.getId() + ":" + to.getId();
                if (existing.contains(key)) {
                    continue;
                }
                StatusTransitionDO transition = new StatusTransitionDO();
                transition.setTenantId(tenantId);
                transition.setTemplateId(templateId);
                transition.setFromNodeId(from.getId());
                transition.setToNodeId(to.getId());
                transition.setName(to.getName());
                transitionDao.insert(transition);
            }
        }
    }

    private Map<String, ExternalStatusOption> orderedStatuses(ExternalWorkitemDetail detail, List<ExternalStatusOption> operationalStatuses) {
        Map<String, ExternalStatusOption> result = new LinkedHashMap<>();
        if (detail.getStatusName() != null && !detail.getStatusName().isBlank()) {
            ExternalStatusOption current = new ExternalStatusOption();
            current.setExternalId(detail.getStatusId());
            current.setName(detail.getStatusName());
            result.put(detail.getStatusName(), current);
        }
        if (operationalStatuses != null) {
            for (ExternalStatusOption status : operationalStatuses) {
                if (status.getName() != null && !status.getName().isBlank()) {
                    result.putIfAbsent(status.getName(), status);
                }
            }
        }
        return result;
    }

    private String templateName(ExternalProjectBindingDO binding, String workType) {
        String project = binding.getExternalProjectName() == null || binding.getExternalProjectName().isBlank()
                ? binding.getExternalProjectId() : binding.getExternalProjectName();
        return binding.getProvider() + " " + project + " " + workType + " 状态";
    }

    private String statusCode(ExternalStatusOption status) {
        if (status.getExternalId() != null && !status.getExternalId().isBlank()) {
            return "aone_" + status.getExternalId();
        }
        return "aone_" + sha1(status.getName()).substring(0, 16);
    }

    private String category(String name, int sort) {
        if (sort == 0 || containsAny(name, "待处理", "open", "new")) {
            return "INIT";
        }
        if (containsAny(name, "已完成", "完成", "fixed", "done", "closed")) {
            return "DONE";
        }
        if (containsAny(name, "取消", "cancel", "won'tfix", "invalid", "duplicate")) {
            return "CANCELED";
        }
        return "IN_PROGRESS";
    }

    private boolean containsAny(String text, String... needles) {
        String lower = text == null ? "" : text.toLowerCase();
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean sameStatus(String left, String right) {
        return left != null && right != null && left.equals(right);
    }

    private String sha1(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
