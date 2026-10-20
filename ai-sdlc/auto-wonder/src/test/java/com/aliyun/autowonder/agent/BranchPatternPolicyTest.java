package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BranchPatternPolicyTest {

    @Test
    void normalizesExactAndTrailingWildcardPatternsInInputOrder() {
        assertEquals(List.of("develop", "feature/*", "release/autowonder-v*"),
                BranchPatternPolicy.normalize(List.of("develop", "feature/*", "develop", "release/autowonder-v*")));
        assertNull(BranchPatternPolicy.encode(null));
        assertNull(BranchPatternPolicy.encode(List.of()));
        assertEquals("[\"develop\",\"feature/*\"]",
                BranchPatternPolicy.encode(List.of("develop", "feature/*")));
        assertEquals(List.of("develop", "feature/*"),
                BranchPatternPolicy.decode("[\"develop\",\"feature/*\"]"));
    }

    @Test
    void rejectsMalformedPatterns() {
        List<String> invalid = List.of("", " feature/*", "feature/* ", "feature/*/nested", "feature/**",
                "\u00a0feature/*", "feature/*\u00a0", "bad\nbranch", "bad..branch", "bad@{branch",
                "bad.lock", "/leading", "trailing/", ".hidden");
        for (String pattern : invalid) {
            assertThrows(BizException.class, () -> BranchPatternPolicy.normalize(List.of(pattern)), pattern);
        }
        assertThrows(BizException.class,
                () -> BranchPatternPolicy.normalize(List.of("a".repeat(256))));
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 33; i++) tooMany.add("branch-" + i);
        assertThrows(BizException.class, () -> BranchPatternPolicy.normalize(tooMany));
    }
    @Test void localizesEveryValidationReasonAndPreservesStoredCorruption() {
        var cases = List.of(
            new Object[]{java.util.Collections.nCopies(33, "develop"), "At most 32 commit branch patterns are allowed"},
            new Object[]{java.util.Arrays.asList((String) null), "Commit branch patterns must not be empty"},
            new Object[]{List.of(" <b>{0}'分支"), "Commit branch patterns must not contain leading or trailing whitespace or control characters:  <b>{0}'分支"},
            new Object[]{List.of("a".repeat(256)), "Commit branch patterns must not exceed 255 UTF-8 bytes"},
            new Object[]{List.of("a**"), "Commit branch patterns allow only one trailing wildcard: a**"},
            new Object[]{List.of("bad..branch"), "Commit branch pattern is not a valid Git branch: bad..branch"});
        for (var item : cases) {
            @SuppressWarnings("unchecked") var patterns = (List<String>) item[0];
            BizException error = assertThrows(BizException.class, () -> BranchPatternPolicy.normalize(patterns));
            assertEquals("10001", error.getCode());
            assertEquals(400, new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleBiz(error).getStatusCodeValue());
            assertBilingual(error, (String) item[1]);
            // Background callers must retain the same carrier rather than lose its metadata.
            if (patterns.get(0) != null) assertBilingual(assertThrows(BizException.class,
                () -> BranchPatternPolicy.decode(com.aliyun.autowonder.json.JSON.toJSONString(patterns))), (String) item[1]);
        }
        BizException stored = assertThrows(BizException.class, () -> BranchPatternPolicy.decode("{broken"));
        assertBilingual(stored, "仓库提交分支规则存储格式不合法");
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
