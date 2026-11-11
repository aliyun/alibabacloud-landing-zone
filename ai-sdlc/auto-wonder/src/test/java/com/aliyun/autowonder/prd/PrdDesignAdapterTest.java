package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter's confirmation gate is the contract between model output and a stored version: only a
 * complete, confirmable design passes, so a half-formed generation never becomes a PRD version.
 * These methods read no injected dependency, so the adapter is built with nulls on purpose.
 */
class PrdDesignAdapterTest {

    private final PrdDesignAdapter adapter = new PrdDesignAdapter(
            null, null, null, null, null, null, null);

    @Test
    void sceneIsTheRegisteredPrdDesignScene() {
        assertEquals(com.aliyun.autowonder.ai.AiConstants.Scene.PRD_DESIGN, adapter.scene());
    }

    @Test
    void systemPromptStatesTheHardConstraints() {
        String prompt = adapter.buildSystemPrompt(session());

        // No tools: the design is derived from the frozen profile, not a fresh repo read.
        assertTrue(prompt.contains("可以读取本次会话提供的截图和附件"));
        assertTrue(prompt.contains("可直接预览的 HTML/CSS 原型"));
        assertTrue(prompt.contains("mockHtml"));
        // Requirement keys stay stable across edits so acceptance history keeps its link.
        assertTrue(prompt.contains("REQ-001"));
        assertTrue(prompt.contains("沿用原 key"));
        // Unknowns and unimplemented interfaces must not be presented as existing capability.
        assertTrue(prompt.contains("TO_BE_CONFIRMED"));
        assertTrue(prompt.contains("EXISTING"));
        assertTrue(prompt.contains("blocking"));
        // The schema is embedded so the model has a concrete shape to fill.
        assertTrue(prompt.contains("acceptanceCriteria"));
        assertTrue(prompt.contains("forbiddenState"));
    }

    @Test
    void acceptsACompleteConfirmableDesign() {
        String json = PrdDesignCodec.serialize(confirmable());

        assertNull(adapter.validateForConfirmation(json));
        assertNull(adapter.validateResult(json));
    }

    @Test
    void rejectsEmptyAndMalformedOutput() {
        BizException empty = adapter.validateForConfirmation(null);
        assertEquals(ErrorCode.AI_CONFIRM_VALIDATION_FAILED.getCode(), empty.getCode());
        assertEquals("ai.validation.null_result", empty.getMessageKey());

        assertNotNull(adapter.validateForConfirmation("   "));

        BizException malformed = adapter.validateForConfirmation("{ not json ");
        assertEquals("ai.validation.invalid_json", malformed.getMessageKey());
    }

    @Test
    void rejectsDesignMissingTitleOrRequirements() {
        PrdDesignData noTitle = confirmable();
        noTitle.setTitle("  ");
        assertEquals("prd.design.title_missing",
                adapter.validateForConfirmation(PrdDesignCodec.serialize(noTitle)).getMessageKey());

        PrdDesignData noRequirements = confirmable();
        noRequirements.setRequirements(new ArrayList<>());
        assertEquals("prd.design.requirements_missing",
                adapter.validateForConfirmation(PrdDesignCodec.serialize(noRequirements)).getMessageKey());
    }

    @Test
    void savesConceptDesignsWithUnresolvedQuestionsForReview() {
        PrdDesignData data = confirmable();
        data.getRequirements().get(0).setAcceptanceCriteria(new ArrayList<>());
        PrdDesignData.OpenQuestion question = new PrdDesignData.OpenQuestion();
        question.setKey("Q-001");
        question.setQuestion("权限模型？");
        question.setBlocking(true);
        data.setOpenQuestions(new ArrayList<>(List.of(question)));
        assertNull(adapter.validateForConfirmation(PrdDesignCodec.serialize(data)));
        assertTrue(!PrdDesignCodec.validateForConfirmation(data).isEmpty());
    }

    @Test
    void aDesignWithOnlyNonBlockingQuestionsPasses() {
        PrdDesignData data = confirmable();
        PrdDesignData.OpenQuestion open = new PrdDesignData.OpenQuestion();
        open.setKey("Q-001");
        open.setQuestion("非阻塞问题");
        open.setBlocking(false);
        data.setOpenQuestions(new ArrayList<>(List.of(open)));

        assertNull(adapter.validateForConfirmation(PrdDesignCodec.serialize(data)));
    }

    private static PrdDesignData confirmable() {
        PrdDesignData data = new PrdDesignData();
        data.setTitle("工单批量导入");
        PrdDesignData.Requirement requirement = new PrdDesignData.Requirement();
        requirement.setKey("REQ-001");
        requirement.setTitle("上传 CSV");
        requirement.setAcceptanceCriteria(new ArrayList<>(List.of("超过 10MB 时拒绝并说明原因")));
        data.setRequirements(new ArrayList<>(List.of(requirement)));
        return data;
    }

    private static com.aliyun.autowonder.ai.AiSessionDO session() {
        com.aliyun.autowonder.ai.AiSessionDO session = new com.aliyun.autowonder.ai.AiSessionDO();
        session.setId(1L);
        session.setTenantId(1L);
        session.setBizRefId(10L);
        session.setScene(com.aliyun.autowonder.ai.AiConstants.Scene.PRD_DESIGN);
        return session;
    }
}
