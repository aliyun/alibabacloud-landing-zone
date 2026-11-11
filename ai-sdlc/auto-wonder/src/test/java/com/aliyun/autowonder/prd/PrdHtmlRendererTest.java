package com.aliyun.autowonder.prd;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The renderer is the structural guarantee behind "HTML and structured data come from the same
 * versioned design": whatever the model returns is re-rendered from parsed design data, so the
 * document can only ever contain what the structured data holds.
 */
class PrdHtmlRendererTest {

    @Test
    void rendersEveryRequiredPrdSection() {
        String html = PrdHtmlRenderer.render(sample());

        for (String heading : List.of("背景", "目标", "用户角色", "功能范围",
                "功能入口及信息架构", "核心用户流程", "页面布局及交互原型", "数据字段与校验规则",
                "接口依赖", "需求与验收标准", "待确认问题", "风险", "变更说明")) {
            assertTrue(html.contains("<h2>" + heading + "</h2>"), "missing section: " + heading);
        }
        // Scope is split into two sub-sections under the "功能范围" heading.
        assertTrue(html.contains("<h3>本次范围</h3>"), "missing in-scope sub-section");
        assertTrue(html.contains("<h3>非目标</h3>"), "missing non-goal sub-section");
        for (String state : List.of("正常", "加载", "空", "错误", "权限不足")) {
            assertTrue(html.contains(">" + state + "</th>"), "missing page state: " + state);
        }
    }

    @Test
    void isDeterministicForTheSameDesignData() {
        String first = PrdHtmlRenderer.render(sample());
        String second = PrdHtmlRenderer.render(sample());

        assertEquals(first, second, "identical input must render identically so digests stay stable");
        assertTrue(first.startsWith("<!DOCTYPE html>"));
        assertFalse(PrdHtmlRenderer.RENDERER_VERSION.isBlank());
    }

    @Test
    void requirementContentMatchesTheStructuredDataExactly() {
        PrdDesignData data = sample();
        String html = PrdHtmlRenderer.render(data);

        for (PrdDesignData.Requirement requirement : data.getRequirements()) {
            assertTrue(html.contains("id=\"req-" + requirement.getKey() + "\""),
                    "requirement anchor missing: " + requirement.getKey());
            assertTrue(html.contains(PrdHtmlRenderer.esc(requirement.getTitle())));
            for (String criterion : requirement.getAcceptanceCriteria()) {
                assertTrue(html.contains(PrdHtmlRenderer.esc(criterion)),
                        "acceptance criterion not rendered: " + criterion);
            }
        }
        // An excluded requirement stays visible but is marked, never silently dropped.
        assertTrue(html.contains("本次排除"));
    }

    @Test
    void escapesAuthorAndModelSuppliedText() {
        PrdDesignData data = new PrdDesignData();
        data.setTitle("</title><script>alert(1)</script>");
        data.setBackground("Tom & Jerry <b>bold</b>");
        PrdDesignData.Requirement requirement = new PrdDesignData.Requirement();
        requirement.setKey("REQ-1");
        requirement.setTitle("<img src=x onerror=alert(1)>");
        requirement.setAcceptanceCriteria(List.of("a < b && c > d"));
        data.setRequirements(List.of(requirement));

        String html = PrdHtmlRenderer.render(data);

        assertFalse(html.contains("<script>alert(1)</script>"), "script must not survive escaping");
        assertFalse(html.contains("<img src=x onerror=alert(1)>"));
        assertFalse(html.contains("<b>bold</b>"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("Tom &amp; Jerry"));
        assertTrue(html.contains("a &lt; b &amp;&amp; c &gt; d"));
    }

    @Test
    void prototypeIsSandboxedWithoutSameOriginAndExternalNetworkIsBlocked() {
        PrdDesignData data = sample();
        data.getPages().get(0).setMockHtml("<button onclick=\"alert(1)\">点我</button>");

        String html = PrdHtmlRenderer.render(data);

        assertTrue(html.contains("sandbox=\"allow-scripts\""), "prototype must allow only scripts");
        assertFalse(html.contains("allow-same-origin"), "prototype must not reach the PRD document");
        assertFalse(html.contains("allow-top-navigation"), "prototype must not escape the host page");
        // The prototype markup goes into srcdoc as an escaped attribute, never as live markup.
        assertTrue(html.contains("srcdoc=\"&lt;button onclick=&quot;alert(1)&quot;&gt;"));
        assertFalse(html.contains("<button onclick=\"alert(1)\">"));
        assertTrue(html.contains("default-src 'none'"), "CSP must forbid external resources");
        assertTrue(html.contains("base-uri 'none'"));
        assertTrue(html.contains("form-action 'none'"));
        assertTrue(html.contains("<meta name=\"referrer\" content=\"no-referrer\">"));
    }

    @Test
    void interfaceStatusDistinguishesExistingFromPlanned() {
        PrdDesignData data = new PrdDesignData();
        PrdDesignData.InterfaceDependency existing = new PrdDesignData.InterfaceDependency();
        existing.setName("查询工单");
        existing.setStatus("EXISTING");
        PrdDesignData.InterfaceDependency planned = new PrdDesignData.InterfaceDependency();
        planned.setName("保存 PRD 版本");
        planned.setStatus("PLANNED");
        PrdDesignData.InterfaceDependency unknown = new PrdDesignData.InterfaceDependency();
        unknown.setName("生成预览");
        unknown.setStatus("TO_BE_CONFIRMED");
        data.setInterfaces(List.of(existing, planned, unknown));

        String html = PrdHtmlRenderer.render(data);

        assertTrue(html.contains(">已有</span>"));
        assertTrue(html.contains(">拟新增</span>"));
        assertTrue(html.contains(">待确认</span>"));
        // The document states outright that planned interfaces are not existing capabilities.
        assertTrue(html.contains("尚未实现"));
    }

    @Test
    void blockingQuestionsAreFlaggedAndResolvedOnesAreNot() {
        PrdDesignData data = new PrdDesignData();
        PrdDesignData.OpenQuestion blocking = new PrdDesignData.OpenQuestion();
        blocking.setKey("Q-1");
        blocking.setQuestion("权限模型如何定义？");
        blocking.setBlocking(true);
        PrdDesignData.OpenQuestion resolved = new PrdDesignData.OpenQuestion();
        resolved.setKey("Q-2");
        resolved.setQuestion("文案语言？");
        resolved.setResolved(true);
        resolved.setResolution("跟随用户选择");
        data.setOpenQuestions(List.of(blocking, resolved));

        String html = PrdHtmlRenderer.render(data);

        assertTrue(html.contains(">阻塞</span>"));
        assertTrue(html.contains(">已解决</span>"));
        assertTrue(html.contains("跟随用户选择"));
        assertEquals(List.of(blocking), data.unresolvedBlockingQuestions());
    }

    @Test
    void missingOptionalContentDegradesInsteadOfFailing() {
        String html = PrdHtmlRenderer.render(new PrdDesignData());

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<h2>需求与验收标准</h2>"));
        assertTrue(html.contains("本版未定义需求项"));
        // Optional sections are simply absent rather than rendered as empty shells.
        assertFalse(html.contains("接口依赖"));
        assertFalse(html.contains("交互原型"));
    }

    @Test
    void englishDocumentLabelsDoNotTranslateUserContent() {
        PrdDesignData data = sample();
        data.setLanguage("en-US");
        data.setTitle("用户自定义标题");
        String html = PrdHtmlRenderer.render(data);
        assertTrue(html.contains("lang=\"en-US\""));
        assertTrue(html.contains("Requirements and acceptance criteria"));
        assertTrue(html.contains("用户自定义标题"));
        assertFalse(html.contains("<h2>需求与验收标准</h2>"));
    }

    static PrdDesignData sample() {
        PrdDesignData data = new PrdDesignData();
        data.setTitle("工单批量导入");
        data.setBackground("运营需要批量导入历史工单。");
        data.setGoals("支持 CSV 批量导入并给出逐行校验结果。");
        data.setUserRoles(List.of("运营", "空间管理员"));
        data.setInScope(List.of("CSV 上传", "逐行校验"));
        data.setNonGoals(List.of("不做定时导入"));
        data.setTargetProjectAdaptation("复用 frontend 应用的 antd Upload 与 ThemedTable。");

        PrdDesignData.FeatureEntry entry = new PrdDesignData.FeatureEntry();
        entry.setLocation("工单列表页操作区");
        entry.setCapability("批量导入");
        data.setFeatureEntries(List.of(entry));

        PrdDesignData.FlowStep step = new PrdDesignData.FlowStep();
        step.setOrder(1);
        step.setActor("运营");
        step.setAction("上传 CSV 并确认导入结果");
        data.setUserFlows(List.of(step));

        PrdDesignData.PageDesign page = new PrdDesignData.PageDesign();
        page.setPageKey("import");
        page.setName("批量导入弹窗");
        page.setRoute("/workitems?import=1");
        page.setLayout("居中弹窗，上部上传区，下部结果表");
        page.setInteractions(List.of("选择文件后展示前 10 行预览"));
        page.setNormalState("展示导入结果表");
        page.setLoadingState("上传中显示进度");
        page.setEmptyState("未选择文件时提示");
        page.setErrorState("解析失败时展示行号与原因");
        page.setForbiddenState("只读成员不显示入口");
        data.setPages(List.of(page));

        PrdDesignData.DataField field = new PrdDesignData.DataField();
        field.setName("title");
        field.setType("string");
        field.setRequired(true);
        field.setValidation("1-200 字符");
        field.setDescription("工单标题");
        data.setDataFields(List.of(field));

        PrdDesignData.InterfaceDependency existing = new PrdDesignData.InterfaceDependency();
        existing.setName("创建工单");
        existing.setMethod("POST");
        existing.setPath("/api/workitems");
        existing.setStatus("EXISTING");
        PrdDesignData.InterfaceDependency planned = new PrdDesignData.InterfaceDependency();
        planned.setName("批量导入");
        planned.setMethod("POST");
        planned.setPath("/api/workitems/batch-import");
        planned.setStatus("PLANNED");
        data.setInterfaces(List.of(existing, planned));

        PrdDesignData.Requirement first = new PrdDesignData.Requirement();
        first.setKey("REQ-001");
        first.setTitle("上传 CSV");
        first.setDescription("支持 UTF-8 编码的 CSV 文件。");
        first.setPriority("P0");
        first.setAcceptanceCriteria(List.of("超过 10MB 时拒绝并说明原因"));

        PrdDesignData.Requirement second = new PrdDesignData.Requirement();
        second.setKey("REQ-002");
        second.setTitle("定时导入");
        second.setExcluded(true);
        second.setAcceptanceCriteria(List.of("本版不实现"));
        data.setRequirements(List.of(first, second));

        PrdDesignData.OpenQuestion question = new PrdDesignData.OpenQuestion();
        question.setKey("Q-001");
        question.setQuestion("重复标题是否允许？");
        question.setBlocking(true);
        data.setOpenQuestions(List.of(question));

        data.setRisks(List.of("大文件解析可能超时"));
        data.setChangeLog("首版。");
        return data;
    }
}
