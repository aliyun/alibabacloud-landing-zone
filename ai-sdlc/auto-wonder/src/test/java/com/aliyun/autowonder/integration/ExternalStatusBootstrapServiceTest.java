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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalStatusBootstrapServiceTest {

    @Test
    void createsProjectSpecificTemplateNodesMappingsAndTransitions() {
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        StatusTransitionDao transitionDao = mock(StatusTransitionDao.class);
        ExternalStatusMappingDao mappingDao = mock(ExternalStatusMappingDao.class);
        List<StatusNodeDO> insertedNodes = new ArrayList<>();

        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            StatusTemplateDO template = invocation.getArgument(0);
            template.setId(700L);
            return null;
        }).when(templateDao).insert(any(StatusTemplateDO.class));
        when(nodeDao.findByTemplateAndCode(anyLong(), anyString())).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            StatusNodeDO node = invocation.getArgument(0);
            node.setId(1000L + insertedNodes.size());
            insertedNodes.add(node);
            return null;
        }).when(nodeDao).insert(any(StatusNodeDO.class));
        when(nodeDao.listByTemplateId(700L)).thenAnswer(invocation -> insertedNodes);
        when(transitionDao.listByTemplateId(700L)).thenReturn(List.of());
        when(mappingDao.findByExternal(anyLong(), anyString(), anyLong(), anyString(), anyString())).thenReturn(null);

        ExternalStatusBootstrapService service = new ExternalStatusBootstrapService(templateDao, nodeDao,
                transitionDao, mappingDao);

        StatusNodeDO selected = service.ensureStatus(binding(), detail(),
                List.of(status("开发中", "229667"), status("完成", "232457")), 9L);

        assertEquals("待处理", selected.getName());
        assertEquals(700L, selected.getTemplateId());
        assertEquals(3, insertedNodes.size());
        assertEquals("INIT", insertedNodes.get(0).getCategory());
        assertEquals("IN_PROGRESS", insertedNodes.get(1).getCategory());
        assertEquals("DONE", insertedNodes.get(2).getCategory());
        ArgumentCaptor<ExternalStatusMappingDO> mappings = ArgumentCaptor.forClass(ExternalStatusMappingDO.class);
        verify(mappingDao, times(3)).insert(mappings.capture());
        ExternalStatusMappingDO currentStatusMapping = mappings.getAllValues().stream()
                .filter(mapping -> "待处理".equals(mapping.getExternalStatusName()))
                .findFirst()
                .orElseThrow();
        assertEquals(selected.getId(), currentStatusMapping.getStatusNodeId());
        assertEquals("100005", currentStatusMapping.getExternalStatusId());
        verify(transitionDao, times(6)).insert(any(StatusTransitionDO.class));

        ArgumentCaptor<StatusTemplateDO> template = ArgumentCaptor.forClass(StatusTemplateDO.class);
        verify(templateDao).insert(template.capture());
        assertEquals("AONE Agent Toolkits REQ 状态", template.getValue().getName());
        assertEquals(0, template.getValue().getIsDefault());
    }

    @Test
    void ensureStatusesBackfillsExternalIssueTypeForExistingMappings() {
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        StatusTransitionDao transitionDao = mock(StatusTransitionDao.class);
        ExternalStatusMappingDao mappingDao = mock(ExternalStatusMappingDao.class);
        StatusTemplateDO template = new StatusTemplateDO();
        template.setId(700L);
        template.setName("AONE Agent Toolkits REQ 状态");
        StatusNodeDO existingNode = new StatusNodeDO();
        existingNode.setId(1000L);
        existingNode.setTemplateId(700L);
        existingNode.setName("待处理");
        ExternalStatusMappingDO existingMapping = new ExternalStatusMappingDO();
        existingMapping.setId(600L);

        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of(template));
        when(nodeDao.findByTemplateAndCode(700L, "aone_100005")).thenReturn(existingNode);
        when(nodeDao.listByTemplateId(700L)).thenReturn(List.of(existingNode));
        when(transitionDao.listByTemplateId(700L)).thenReturn(List.of());
        when(mappingDao.findByExternal(100L, "AONE", 1L, "REQ", "待处理")).thenReturn(existingMapping);

        ExternalStatusBootstrapService service = new ExternalStatusBootstrapService(templateDao, nodeDao,
                transitionDao, mappingDao);

        service.ensureStatuses(binding(), "REQ", "9", List.of(status("待处理", "100005")), 9L);

        verify(mappingDao).updateExternalIssueType(600L, "9");
    }

    private ExternalProjectBindingDO binding() {
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setId(1L);
        binding.setTenantId(100L);
        binding.setProvider("AONE");
        binding.setExternalProjectId("2161074");
        binding.setExternalProjectName("Agent Toolkits");
        return binding;
    }

    private ExternalWorkitemDetail detail() {
        ExternalWorkitemDetail detail = new ExternalWorkitemDetail();
        detail.setExternalId("84189105");
        detail.setWorkType("REQ");
        detail.setStatusId("100005");
        detail.setStatusName("待处理");
        return detail;
    }

    private ExternalStatusOption status(String name, String id) {
        ExternalStatusOption status = new ExternalStatusOption();
        status.setName(name);
        status.setExternalId(id);
        return status;
    }
}
