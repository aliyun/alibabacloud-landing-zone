package com.aliyun.autowonder.memory.store;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MemoryTopicFormatTest {
    @Test void targetLinkInAnotherEntriesHookDoesNotIdentifyThatEntry() {
        String other = "- [Other](other.md) — compare [Target](target.md)\n";
        String index = other + "- [Target](target.md) — own hook\n";
        assertTrue(MemoryIndexPolicy.validateIndex(index, path -> true).isEmpty());
        assertEquals(other, MemoryTopicFormat.removeIndex(index, "target.md"));
        assertEquals(other + "- [Changed](target.md) — new hook\n",
                MemoryTopicFormat.upsertIndex(index, "target.md", "Changed", "new hook"));
    }

    @Test void unsafeTitleCannotBreakIndexAndLongHookKeepsLink() {
        String index = MemoryTopicFormat.upsertIndex("", "manual_1.md", "发布 [约定]\n下一行",
                "同步时参考".repeat(100));
        assertEquals(1, index.lines().count());
        assertTrue(index.contains("](manual_1.md)"));
        assertTrue(MemoryIndexPolicy.validateIndex(index, p -> p.equals("manual_1.md")).isEmpty());
    }

    @Test void preservesBodyWhitespaceAndUnknownMetadataOnEdit() {
        String body = "  前导空格\n正文\n\n";
        String original = "---\ntype: reference\nsource_memory_id: 41\ntitle: 旧标题\n---\n" + body;
        String edited = MemoryTopicFormat.render("legacy_41", "feedback", "新标题", "同步时",
                body, MemoryTopicFormat.parse(original).metadata());
        assertTrue(edited.endsWith("\n---\n" + body));
        assertEquals(41, MemoryTopicFormat.parse(edited).metadata().get("source_memory_id"));
        assertEquals(body, MemoryTopicFormat.parse(edited).body());
        MemoryDocumentValidator.requireCanonicalTopic("legacy_41.md", edited);
    }

    @Test void upsertAndRemoveOnlyChangeTargetEntry() {
        String before = "- [Other](other.md) — 保留说明\n- [Old](legacy_1.md) — old\n";
        String updated = MemoryTopicFormat.upsertIndex(before, "legacy_1.md", "新标题", "新用途");
        assertEquals("- [Other](other.md) — 保留说明\n- [新标题](legacy_1.md) — 新用途\n", updated);
        assertEquals("- [Other](other.md) — 保留说明\n", MemoryTopicFormat.removeIndex(updated, "legacy_1.md"));
    }

    @Test void parsesOnlyFrontmatterNotBodyFields() {
        var parsed = MemoryTopicFormat.parse("---\ntype: project\ntitle: 正确\n---\n标题\ntitle: 错误\n");
        assertEquals("正确", parsed.metadata().get("title"));
        assertEquals("标题\ntitle: 错误\n", parsed.body());
    }

    @Test void yamlSpecialCharactersRemainLiteral() {
        String value = "标题: \"quoted\" # note";
        String content = MemoryTopicFormat.render("manual_1", "user", value, "当我说 yes: no", "正文", Map.of());
        assertEquals(value, MemoryTopicFormat.parse(content).metadata().get("title"));
    }
}
