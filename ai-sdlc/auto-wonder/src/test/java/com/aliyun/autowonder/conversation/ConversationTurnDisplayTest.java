package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.conversation.dto.ClarificationTurnVO;
import com.aliyun.autowonder.conversation.dto.PlatformTurnVO;
import com.aliyun.autowonder.json.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ConversationTurnDisplayTest {
    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "EMPTY_OUTPUT", "REPLY_FAILED", "DISPATCH_REPLY_FAILED",
            "DISPATCH_FAILED", "EXECUTOR_COMPATIBILITY_FAILED", "DELIVERY_ATTEMPTS_EXHAUSTED"})
    void allSevenFiniteEntriesRoundtripWithExactRawArguments(String code) {
        boolean content = java.util.Set.of("CANCELED", "EMPTY_OUTPUT", "REPLY_FAILED", "DISPATCH_REPLY_FAILED").contains(code);
        String raw = "\n<b>响应已终止</b> **raw** {{detail}} ' &\r";
        Map<String, Object> args = "DELIVERY_ATTEMPTS_EXHAUSTED".equals(code) ? Map.of("attempts", 3)
                : java.util.Set.of("CANCELED", "EMPTY_OUTPUT").contains(code) ? Map.of() : Map.of("detail", raw);
        String source = ConversationTurnDisplay.source("WORKITEM_CLARIFICATION", content ? "content" : "error", code, args);
        var projection = ConversationTurnDisplay.project("PLATFORM_ASSISTANT", source);
        assertNotNull(projection);
        var entry = content ? projection.content() : projection.error();
        assertEquals(code, entry.code()); assertEquals(args, entry.args());
        assertNull(content ? projection.error() : projection.content());
        assertEquals(source, JSON.toJSONString(Map.of("conversationDisplay", projection)));
        for (String channel : java.util.List.of("DINGTALK", "FEISHU", "PLATFORM_INTERNAL", "unknown")) {
            assertNull(ConversationTurnDisplay.project(channel, source));
            assertNull(ConversationTurnDisplay.source(channel, content ? "content" : "error", code, args));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{", "{}", "{\"conversationDisplay\":[]}",
            "{\"conversationDisplay\":{\"content\":{\"code\":\"UNKNOWN\",\"args\":{}}}}",
            "{\"conversationDisplay\":{\"content\":{\"code\":\"CANCELED\"}}}",
            "{\"conversationDisplay\":{\"content\":{\"code\":\"CANCELED\",\"args\":{\"secret\":\"raw\"}}}}",
            "{\"conversationDisplay\":{\"content\":{\"code\":\"REPLY_FAILED\",\"args\":{}}}}",
            "{\"conversationDisplay\":{\"content\":{\"code\":\"REPLY_FAILED\",\"args\":{\"detail\":3}}}}",
            "{\"conversationDisplay\":{\"error\":{\"code\":\"CANCELED\",\"args\":{}}}}",
            "{\"conversationDisplay\":{\"error\":{\"code\":\"DELIVERY_ATTEMPTS_EXHAUSTED\",\"args\":{\"attempts\":\"3\"}}}}",
            "{\"conversationDisplay\":{\"error\":{\"code\":\"DELIVERY_ATTEMPTS_EXHAUSTED\",\"args\":{\"attempts\":-1}}}}",
            "{\"conversationDisplay\":{\"error\":{\"code\":\"DELIVERY_ATTEMPTS_EXHAUSTED\",\"args\":{\"attempts\":1.5}}}}",
            "{\"conversationDisplay\":{\"error\":{\"code\":\"DELIVERY_ATTEMPTS_EXHAUSTED\",\"args\":{\"attempts\":2147483648}}}}"})
    void malformedUnknownOrIncompleteMetadataRetainsRawFallback(String source) {
        assertNull(ConversationTurnDisplay.project("WORKITEM_CLARIFICATION", source));
    }

    @Test
    void projectsEntriesIndependentlyAndNeverCopiesPrivateContext() {
        String source = "{\"senderNick\":\"private\",\"secret\":\"private\",\"conversationDisplay\":{"
                + "\"content\":{\"code\":\"CANCELED\",\"args\":{}},"
                + "\"error\":{\"code\":\"UNKNOWN\",\"args\":{\"detail\":\"private\"}}}}";
        var projected = ConversationTurnDisplay.project("WORKITEM_CLARIFICATION", source);
        assertNotNull(projected.content()); assertNull(projected.error());
        assertFalse(JSON.toJSONString(projected).contains("private"));
        assertNull(ConversationTurnDisplay.project("WORKITEM_CLARIFICATION", null));
        assertNull(ConversationTurnDisplay.project("WORKITEM_CLARIFICATION", " ".repeat(65_537)));
    }

    @Test
    void absentDisplayIsOmittedWhileEachDtoRetainsItsNullableFields() {
        ObjectMapper mapper = new ObjectMapper();
        var sharedFields = java.util.Set.of("id", "direction", "content", "status", "error", "gmtCreate");
        var platformFields = new java.util.HashSet<>(sharedFields);
        platformFields.add("images");
        var clarificationFields = new java.util.HashSet<>(sharedFields);
        clarificationFields.add("attachments");
        for (Object value : java.util.List.of(ClarificationTurnVO.builder().build(), PlatformTurnVO.builder().build())) {
            var json = mapper.valueToTree(value);
            var actualFields = new java.util.HashSet<String>();
            json.fieldNames().forEachRemaining(actualFields::add);
            var expectedFields = value instanceof PlatformTurnVO ? platformFields : clarificationFields;
            assertEquals(expectedFields, actualFields);
            for (String field : expectedFields) {
                assertTrue(json.has(field)); assertTrue(json.get(field).isNull());
            }
        }
    }

    @Test
    void populatedImagesAndDisplayPreserveTheirPublicPayloads() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var display = ConversationTurnDisplay.project("PLATFORM_ASSISTANT",
                ConversationTurnDisplay.source("PLATFORM_ASSISTANT", "content", "REPLY_FAILED",
                        Map.of("detail", "<b>原始响应</b>")));
        var image = new PlatformConversationImageService.Image("image-1", "<b>截图</b>.png",
                "image/png", 42L, "/api/platform/images/image-1");
        var platform = mapper.valueToTree(PlatformTurnVO.builder()
                .images(java.util.List.of(image)).display(display).build());
        assertEquals(mapper.readTree("""
                {"id":null,"direction":null,"content":null,"status":null,"error":null,"gmtCreate":null,
                 "images":[{"id":"image-1","name":"<b>截图</b>.png","contentType":"image/png",
                            "size":42,"url":"/api/platform/images/image-1"}],
                 "display":{"content":{"code":"REPLY_FAILED","args":{"detail":"<b>原始响应</b>"}}}}
                """), mapper.readTree(mapper.writeValueAsString(platform)));
        var clarification = mapper.valueToTree(ClarificationTurnVO.builder().display(display).build());
        assertEquals(mapper.readTree("""
                {"id":null,"direction":null,"content":null,"status":null,"error":null,"gmtCreate":null,
                 "attachments":null,
                 "display":{"content":{"code":"REPLY_FAILED","args":{"detail":"<b>原始响应</b>"}}}}
                """), mapper.readTree(mapper.writeValueAsString(clarification)));
    }

    @Test
    void atomicContextUpdatesPreserveExactOriginalCasPredicatesAndMappingWithoutDatabase() throws Exception {
        Configuration config = new Configuration();
        try (var in = getClass().getResourceAsStream("/mapping/AgentConversationTurnDao.xml")) {
            new XMLMapperBuilder(in, config, "mapping/AgentConversationTurnDao.xml", config.getSqlFragments()).parse();
        }
        String prefix = AgentConversationTurnDao.class.getName() + ".";
        for (String[] pair : java.util.List.of(
                new String[]{"updateInboundStatusIfProcessing", "updateInboundStatusAndContextIfProcessing"},
                new String[]{"updateStatusIfCurrent", "updateStatusAndContextIfCurrent"})) {
            String old = config.getMappedStatement(prefix + pair[0]).getBoundSql(Map.of()).getSql().replaceAll("\\s+", " ");
            String fresh = config.getMappedStatement(prefix + pair[1]).getBoundSql(Map.of()).getSql().replaceAll("\\s+", " ");
            assertEquals(old.substring(old.indexOf("WHERE")), fresh.substring(fresh.indexOf("WHERE")));
            assertTrue(fresh.contains("source_context = CASE WHEN source_context IS NULL THEN ? ELSE source_context END"));
            var parameters = config.getMappedStatement(prefix + pair[1]).getBoundSql(Map.of()).getParameterMappings();
            assertEquals("sourceContext", parameters.get(2).getProperty());
        }
        assertTrue(config.getResultMap(prefix + "BaseResultMap").getResultMappings().stream()
                .anyMatch(mapping -> "source_context".equals(mapping.getColumn()) && "sourceContext".equals(mapping.getProperty())));
        assertTrue(config.getMappedStatement(prefix + "insert").getBoundSql(new AgentConversationTurnDO())
                .getParameterMappings().stream().anyMatch(mapping -> "sourceContext".equals(mapping.getProperty())));
    }
}
