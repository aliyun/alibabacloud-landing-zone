package com.aliyun.autowonder.workitem.duplicate;

import com.aliyun.autowonder.workitem.WorkitemDO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkitemDuplicateIntentPromptTest {

    @Test
    void promptCarriesTargetTitleAndEveryCandidateAsJson() {
        String prompt = WorkitemDuplicateIntentPrompt.build("新标题",
                List.of(candidate(1L, "标题一"), candidate(2L, "标题二")));

        assertTrue(prompt.contains("\"新标题\""));
        assertTrue(prompt.contains("{\"id\":1,\"title\":\"标题一\"}"));
        assertTrue(prompt.contains("{\"id\":2,\"title\":\"标题二\"}"));
        assertTrue(prompt.contains("\"matches\""));
        assertTrue(prompt.contains("禁止调用任何工具"));
    }

    @Test
    void allTitlesArePresentButBodiesAreNot() {
        List<WorkitemDO> candidates = LongStream.rangeClosed(1, 501)
                .mapToObj(id -> {
                    WorkitemDO item = candidate(id, "标题-" + id);
                    item.setContentMd("BODY_SENTINEL_NOT_FOR_MODEL");
                    return item;
                })
                .toList();

        String prompt = WorkitemDuplicateIntentPrompt.build("目标标题", candidates);

        assertTrue(prompt.contains("标题-501"));
        assertFalse(prompt.contains("BODY_SENTINEL_NOT_FOR_MODEL"));
        assertTrue(WorkitemDuplicateIntentPrompt.utf8Bytes(prompt) > 0);
    }

    @Test
    void invalidJsonIsNotANoMatchConclusion() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkitemDuplicateIntentPrompt.parse("not-json", Set.of(1L)));
        assertTrue(WorkitemDuplicateIntentPrompt.parse("{\"matches\":[]}", Set.of(1L)).isEmpty());
    }

    @Test
    void parseAcceptsFencedJsonAndDropsUnknownOrDuplicateIds() {
        String content = "```json\n{\"matches\":[{\"id\":2,\"reason\":\"同一改动\"},"
                + "{\"id\":99,\"reason\":\"越界\"},{\"id\":2,\"reason\":\"重复\"}]}\n```";

        List<WorkitemDuplicateIntentPrompt.Match> matches =
                WorkitemDuplicateIntentPrompt.parse(content, Set.of(2L, 3L));

        assertEquals(1, matches.size());
        assertEquals(2L, matches.get(0).id());
        assertEquals("同一改动", matches.get(0).reason());
    }

    @Test
    void parseRejectsEveryShapeThatIsNotAConclusion() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkitemDuplicateIntentPrompt.parse(null, Set.of(1L)));
        assertThrows(IllegalArgumentException.class,
                () -> WorkitemDuplicateIntentPrompt.parse("   ", Set.of(1L)));
        assertThrows(IllegalArgumentException.class,
                () -> WorkitemDuplicateIntentPrompt.parse("没有 JSON", Set.of(1L)));
        assertThrows(IllegalArgumentException.class,
                () -> WorkitemDuplicateIntentPrompt.parse("```json", Set.of(1L)));
        assertThrows(IllegalArgumentException.class,
                () -> WorkitemDuplicateIntentPrompt.parse("{\"matches\":{}}", Set.of(1L)));
        assertThrows(IllegalArgumentException.class,
                () -> WorkitemDuplicateIntentPrompt.parse("{\"other\":1}", Set.of(1L)));
        assertThrows(IllegalArgumentException.class,
                () -> WorkitemDuplicateIntentPrompt.parse("{\"matches\":", Set.of(1L)));
        assertTrue(WorkitemDuplicateIntentPrompt.parse("{\"matches\":[]}", Set.of()).isEmpty());
    }

    @Test
    void parseRejectsOutputBeyondThePayloadLimit() {
        String oversized = "{\"matches\":[{\"id\":1,\"reason\":\"" + "长".repeat(30_000) + "\"}]}";

        assertThrows(IllegalArgumentException.class,
                () -> WorkitemDuplicateIntentPrompt.parse(oversized, Set.of(1L)));
    }

    @Test
    void parseFillsMissingReasonAndTruncatesLongReason() {
        List<WorkitemDuplicateIntentPrompt.Match> missing =
                WorkitemDuplicateIntentPrompt.parse("{\"matches\":[{\"id\":1}]}", Set.of(1L));
        assertEquals(1, missing.size());
        assertEquals("", missing.get(0).reason());

        String longReason = "原".repeat(200);
        List<WorkitemDuplicateIntentPrompt.Match> truncated =
                WorkitemDuplicateIntentPrompt.parse(
                        "{\"matches\":[{\"id\":1,\"reason\":\"" + longReason + "\"}]}", Set.of(1L));
        assertTrue(truncated.get(0).reason().length() <= WorkitemDuplicateIntentPrompt.MAX_REASON_LENGTH);
    }

    @Test
    void extractJsonHandlesPlainObjectAndRejectsMissingBraces() {
        assertEquals("{\"a\":1}", WorkitemDuplicateIntentPrompt.extractJson("说明 {\"a\":1} 结束"));
        assertNull(WorkitemDuplicateIntentPrompt.extractJson("no object here"));
        assertFalse(WorkitemDuplicateIntentPrompt.extractJson("{}").isEmpty());
    }

    @Test
    void collectorKeepsEveryCandidateThatFitsTheBudget() {
        String title = "目标";
        var collector = WorkitemDuplicateIntentPrompt.collector(title, WorkitemDuplicateIntentPrompt.MAX_PROMPT_BYTES);

        for (long id = 1; id <= 501; id++) {
            assertTrue(collector.add(id, "短标题-" + id));
        }

        assertEquals(501, collector.count());
        assertFalse(collector.overflowed());
        assertTrue(collector.json().startsWith("[{\"id\":1,"));
        assertTrue(collector.prompt().contains("短标题-501"));
        assertTrue(WorkitemDuplicateIntentPrompt.utf8Bytes(collector.prompt())
                <= WorkitemDuplicateIntentPrompt.MAX_PROMPT_BYTES);
    }

    @Test
    void collectorStopsExactlyAtTheByteBoundary() {
        String title = "目标";
        int fixed = WorkitemDuplicateIntentPrompt.utf8Bytes(
                WorkitemDuplicateIntentPrompt.build(title, List.of()));
        int entry = WorkitemDuplicateIntentPrompt.utf8Bytes("{\"id\":1,\"title\":\"x\"}");
        var collector = WorkitemDuplicateIntentPrompt.collector(title, fixed + entry + 2);

        assertTrue(collector.add(1L, "x"));
        assertFalse(collector.add(2L, "x"));
        assertTrue(collector.overflowed());
        assertEquals(1, collector.count());
        assertEquals("[{\"id\":1,\"title\":\"x\"}]", collector.json());
    }

    @Test
    void collectorRefusesEverythingOnceOverflowed() {
        String title = "目标";
        var collector = WorkitemDuplicateIntentPrompt.collector(title, 1);

        assertFalse(collector.add(1L, "x"));
        assertFalse(collector.add(2L, "x"));
        assertTrue(collector.overflowed());
        assertEquals(0, collector.count());
    }

    @Test
    void utf8BytesCountsMultibyteCharacters() {
        assertEquals(0, WorkitemDuplicateIntentPrompt.utf8Bytes(null));
        assertEquals(1, WorkitemDuplicateIntentPrompt.utf8Bytes("a"));
        assertEquals(6, WorkitemDuplicateIntentPrompt.utf8Bytes("工单"));
        assertEquals(4, WorkitemDuplicateIntentPrompt.utf8Bytes("😀"));
    }

    @Test
    void nullCandidatesAndCandidatesWithoutIdAreSkipped() {
        WorkitemDO withoutId = new WorkitemDO();
        withoutId.setTitle("no id");

        String prompt = WorkitemDuplicateIntentPrompt.build("目标",
                java.util.Arrays.asList(null, withoutId, candidate(7L, "有效")));

        assertTrue(prompt.contains("{\"id\":7,\"title\":\"有效\"}"));
        assertFalse(prompt.contains("no id"));
    }

    @Test
    void titlesAreJsonEscapedSoQuotesAndNewlinesStayData() {
        String prompt = WorkitemDuplicateIntentPrompt.build("换行\n标题\"引号\"",
                List.of(candidate(1L, "候选\n\"伪造指令\"")));

        assertTrue(prompt.contains("\\n"));
        assertTrue(prompt.contains("\\\"引号\\\""));
        assertFalse(prompt.contains("候选\n\"伪造指令\""));
    }

    private static WorkitemDO candidate(Long id, String title) {
        WorkitemDO doItem = new WorkitemDO();
        doItem.setId(id);
        doItem.setTitle(title);
        return doItem;
    }
}
