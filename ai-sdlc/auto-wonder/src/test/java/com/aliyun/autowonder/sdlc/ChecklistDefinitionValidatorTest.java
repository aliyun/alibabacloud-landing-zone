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
}
