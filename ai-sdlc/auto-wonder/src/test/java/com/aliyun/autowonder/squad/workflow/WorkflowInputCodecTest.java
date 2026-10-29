package com.aliyun.autowonder.squad.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 已落库的输入必须能原样重建，且重建后的哈希仍与提交校验使用的哈希一致。 */
class WorkflowInputCodecTest {

    @Test
    void rebuildsTheTypedInputFromTheStoredPayload() {
        String payload = "{\"analysisVersion\":\"squad-workflow-v1\",\"workspaceId\":100,"
                + "\"squadId\":5,\"members\":[{\"agentId\":\"11\",\"displayName\":\"实现者\","
                + "\"roleName\":\"开发\",\"roleCode\":\"AW_FS_DEV\",\"onlineVersionId\":\"88\","
                + "\"sdlcId\":\"7\",\"sdlcName\":\"交付流程\",\"sdlcWorkType\":\"REQ\","
                + "\"steps\":[{\"stepId\":\"21\",\"order\":1,\"name\":\"实现\",\"kind\":\"EXECUTE\","
                + "\"required\":true}],\"missingConfiguration\":[\"NO_ONLINE_VERSION\"]}],"
                + "\"sources\":[{\"sourceId\":\"agent:11:agent-md\",\"agentId\":\"11\","
                + "\"versionId\":\"88\",\"documentType\":\"AGENT_MD\",\"fieldPath\":\"responsibilities\","
                + "\"stepId\":null,\"stepOrder\":null,\"content\":\"负责实现需求\"}]}";

        WorkflowInput input = WorkflowInputCodec.fromPayload(100L, 5L, payload);

        assertEquals(100L, input.workspaceId());
        assertEquals(5L, input.squadId());
        assertEquals(WorkflowInput.ANALYSIS_VERSION, input.analysisVersion());
        assertEquals(WorkflowInputAssembler.sha256Hex(payload), input.inputHash());
        assertEquals(List.of("NO_ONLINE_VERSION"), input.members().get(0).missingConfiguration());
        assertEquals("21", input.members().get(0).steps().get(0).stepId());
        assertEquals(1, input.members().get(0).steps().get(0).order());
        assertEquals("负责实现需求", input.sources().get(0).content());
        assertNull(input.sources().get(0).stepId());
    }

    @Test
    void defaultsTheAnalysisVersionWhenThePayloadOmitsIt() {
        WorkflowInput input = WorkflowInputCodec.fromPayload(100L, 5L,
                "{\"members\":[],\"sources\":[]}");

        assertEquals(WorkflowInput.ANALYSIS_VERSION, input.analysisVersion());
        assertTrue(input.members().isEmpty());
        assertTrue(input.sources().isEmpty());
    }

    @Test
    void rejectsAPayloadThatIsNotReadable() {
        assertThrows(IllegalStateException.class,
                () -> WorkflowInputCodec.fromPayload(100L, 5L, "not-json"));
        assertThrows(IllegalStateException.class,
                () -> WorkflowInputCodec.fromPayload(100L, 5L, null));
    }
}
