package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.agent.dto.CreateAgentRequest;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.squad.SquadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

/**
 * POST /api/agents 的 JSON 契约：新增的可选 sdlcId 必须经真实 Jackson 反序列化进入 DTO，
 * 并由控制层原样透传给服务层；旧客户端不传该字段时保持 null。
 */
class AgentCreateSdlcBindingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    private AgentController controller(AgentService service) {
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(7L);
        return new AgentController(service, mock(SquadService.class));
    }

    @Test
    void createDeserializesSdlcIdFromJsonAndPassesItToService() throws Exception {
        AgentService service = mock(AgentService.class);
        AgentVO vo = new AgentVO();
        vo.setId(1L);
        when(service.create(any(CreateAgentRequest.class), eq(100L), eq(7L))).thenReturn(vo);

        CreateAgentRequest req = mapper.readValue(
                "{\"name\":\"绑定流程\",\"roleName\":\"前端开发工程师\",\"roleCode\":\"FRONTEND_DEV\",\"sdlcId\":7}",
                CreateAgentRequest.class);

        controller(service).create(req);

        verify(service).create(
                argThat((CreateAgentRequest r) -> Long.valueOf(7L).equals(r.getSdlcId())), eq(100L), eq(7L));
    }

    @Test
    void createKeepsLegacyPayloadWithoutSdlcIdCompatible() throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.create(any(CreateAgentRequest.class), eq(100L), eq(7L))).thenReturn(new AgentVO());

        CreateAgentRequest req = mapper.readValue(
                "{\"name\":\"旧客户端\",\"roleName\":\"前端开发工程师\",\"roleCode\":\"FRONTEND_DEV\"}",
                CreateAgentRequest.class);

        controller(service).create(req);

        verify(service).create(
                argThat((CreateAgentRequest r) -> r.getSdlcId() == null), eq(100L), eq(7L));
    }

    @Test
    void createTreatsExplicitNullSdlcIdAsUnbound() throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.create(any(CreateAgentRequest.class), eq(100L), eq(7L))).thenReturn(new AgentVO());

        CreateAgentRequest req = mapper.readValue(
                "{\"name\":\"清空流程\",\"roleName\":\"前端开发工程师\",\"roleCode\":\"FRONTEND_DEV\",\"sdlcId\":null}",
                CreateAgentRequest.class);

        assertNull(req.getSdlcId());
        controller(service).create(req);

        verify(service).create(
                argThat((CreateAgentRequest r) -> r.getSdlcId() == null), eq(100L), eq(7L));
    }
}
