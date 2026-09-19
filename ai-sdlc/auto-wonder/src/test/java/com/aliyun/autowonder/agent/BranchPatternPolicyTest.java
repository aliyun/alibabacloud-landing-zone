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
}
