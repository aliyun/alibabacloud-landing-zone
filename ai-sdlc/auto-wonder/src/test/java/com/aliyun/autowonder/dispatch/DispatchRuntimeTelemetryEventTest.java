package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class DispatchRuntimeTelemetryEventTest {

    private DispatchDao dispatchDao;
    private DispatchRuntimeEventDao runtimeEventDao;
    private DispatchService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        service = new DispatchService(dispatchDao, runtimeEventDao,
                mock(com.aliyun.autowonder.workitem.WorkitemDao.class),
                mock(com.aliyun.autowonder.agent.AgentDao.class),
                mock(com.aliyun.autowonder.agent.AgentVersionDao.class),
                mock(ExecutorSelector.class),
                mock(PackageContextAssembler.class),
                mock(com.aliyun.autowonder.taskpackage.TaskPackager.class),
                mock(DispatchTransport.class),
                mock(SdlcDriver.class),
                null);
    }

    @Test
    void persistsStructuredTelemetryEventsForEvolution() {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(44L);
        dispatch.setTenantId(1L);
        dispatch.setWorkitemId(10L);
        dispatch.setAgentId(7L);
        dispatch.setStatus(DispatchStatus.RUNNING);
        dispatch.setVersion(1);
        when(dispatchDao.findById(44L)).thenReturn(dispatch);

        JSONObject frame = new JSONObject();
        frame.put("runtimeEvent", new JSONObject()
                .fluentPut("eventId", "44:7")
                .fluentPut("seq", 7L)
                .fluentPut("eventType", "skill.loaded")
                .fluentPut("name", "checkout-safety")
                .fluentPut("provider", "codex"));

        service.onProgress(1L, 44L, frame);

        verify(runtimeEventDao).insert(argThat(e -> "44:7".equals(e.getEventId())
                && Long.valueOf(7L).equals(e.getSeq())
                && "skill.loaded".equals(e.getEventType())
                && e.getDetailJson().contains("\"name\":\"checkout-safety\"")));
    }

    @Test
    void acceptsTheWholeCanonicalRuntimeVocabulary() {
        for (String eventType : new String[]{
                "workspace.created", "bootstrap.generated", "repo.prepared", "upload.completed",
                "plugin.loaded", "subagent.completed", "session.resumed", "turn.started", "llm.usage",
                "mcp.call", "cli.call", "bash.result"
        }) {
            org.junit.jupiter.api.Assertions.assertTrue(DispatchService.isRuntimeEventType(eventType), eventType);
        }
    }

    @Test
    void canonicalRuntimeEventDoesNotInheritOuterProgressName() {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(45L);
        dispatch.setTenantId(1L);
        dispatch.setWorkitemId(10L);
        dispatch.setAgentId(7L);
        dispatch.setStatus(DispatchStatus.RUNNING);
        when(dispatchDao.findById(45L)).thenReturn(dispatch);

        JSONObject frame = new JSONObject()
                .fluentPut("name", "performance")
                .fluentPut("runtimeEvent", new JSONObject()
                        .fluentPut("eventId", "45:1")
                        .fluentPut("eventType", "llm.started")
                        .fluentPut("model", "qmodel_latest"));

        service.onProgress(1L, 45L, frame);

        ArgumentCaptor<DispatchRuntimeEventDO> captor = ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEventDao).insert(captor.capture());
        org.junit.jupiter.api.Assertions.assertFalse(captor.getValue().getDetailJson().contains("performance"));
    }
}
