package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChecklistDefinitionValidatorTest {
    @Test void acceptsLegacyChecksAndExplicitConditionalChecks() {
        assertDoesNotThrow(() -> ChecklistDefinitionValidator.validate(null));
        assertDoesNotThrow(() -> ChecklistDefinitionValidator.validate("[\"完成汇报\",{\"id\":\"tests\",\"text\":\"测试通过\"}]"));
        assertDoesNotThrow(() -> ChecklistDefinitionValidator.validate("[{\"id\":\"tests\",\"text\":\"测试通过\",\"allowNotApplicable\":true,\"notApplicableWhen\":\"仅澄清，无代码变更\"}]"));
    }

    @Test void refusesUnconditionalOrMalformedExceptions() {
        for (String json : new String[]{
                "[{\"id\":\"tests\",\"text\":\"测试通过\",\"allowNotApplicable\":true}]",
                "[{\"id\":\"tests\",\"text\":\"测试通过\",\"allowNotApplicable\":true,\"notApplicableWhen\":\"  \"}]",
                "[{\"id\":\"tests\",\"text\":\"测试通过\",\"allowNotApplicable\":\"true\"}]",
                "[\"汇报\",{\"id\":\"cl_0\",\"text\":\"重复\"}]", "{}", "[42]"}) {
            assertThrows(BizException.class, () -> ChecklistDefinitionValidator.validate(json), json);
        }
    }
    @Test void localizesEveryDefinitionReasonWithLiteralIds() {
        String id = "<b>{0}'测试";
        String obj = "{\"id\":\"" + id + "\",\"text\":\"raw text\"";
        String[][] cases = {
            {"{broken", "checklistJson is not valid JSON"},
            {"{}", "checklistJson must be an array"},
            {"[" + obj + ",\"allowNotApplicable\":\"true\"}]", "checklistJson check " + id + " must have a boolean allowNotApplicable"},
            {"[" + obj + ",\"allowNotApplicable\":true}]", "checklistJson check " + id + " requires a notApplicableWhen condition when opting out is allowed"},
            {"[42]", "checklistJson item 1 must be nonempty text or an object with nonempty id and text"},
            {"[" + obj + "}," + obj + "}]", "checklistJson has a duplicate check id: " + id}};
        for (var item : cases) {
            var error = assertThrows(BizException.class, () -> ChecklistDefinitionValidator.validate(item[0]));
            assertEquals("10001", error.getCode());
            assertBilingual(error, item[1]);
        }
    }

    private static void assertBilingual(com.aliyun.autowonder.common.error.BizException error, String english) {
        var handler = new com.aliyun.autowonder.common.web.GlobalExceptionHandler();
        String diagnostic = error.getMessage();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.CHINA);
            assertEquals(diagnostic, handler.handleBiz(error).getBody().getMessage());
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            assertEquals(english, handler.handleBiz(error).getBody().getMessage());
            assertEquals(diagnostic, error.getMessage());
            assertEquals(diagnostic, error.getLocalizedMessage());
            assertNull(error.getCause());
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

}
