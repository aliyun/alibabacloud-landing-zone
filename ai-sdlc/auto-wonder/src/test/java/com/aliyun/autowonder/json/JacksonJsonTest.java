package com.aliyun.autowonder.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JacksonJsonTest {

    @Test
    void serializesMapKeysAsValidJsonWithoutReferenceAliases() {
        Map<Long, Long> revisions = Map.of(10002L, 2L);
        Map<String, Object> frame = Map.of(
                "environmentVariables", Map.of(),
                "memoryStartingRevisions", revisions);

        String json = JSON.toJSONString(frame);

        assertEquals(2L, JSON.parseObject(json)
                .getJSONObject("memoryStartingRevisions")
                .getLongValue("10002"));
        assertFalse(json.contains("$ref"));
    }

    @Test
    void wrapsNestedObjectsAndArraysAndConvertsPojoCollections() {
        JSONObject root = JSON.parseObject("{\"items\":[{\"name\":\"alpha\"}],\"enabled\":true}");

        assertInstanceOf(JSONArray.class, root.get("items"));
        assertInstanceOf(JSONObject.class, root.getJSONArray("items").get(0));
        assertEquals("alpha", root.getJSONArray("items").getJSONObject(0).getString("name"));
        assertEquals(List.of(new Sample("alpha")), root.getJSONArray("items").toJavaList(Sample.class));
    }

    @Test
    void sharedEmptyAndPopulatedObjectsRemainLiteralJsonWithSdkCompatibilityPresent() throws Exception {
        for (Map<String, Object> shared : List.<Map<String, Object>>of(
                Map.of(), Map.of("text", "中文\n\"quoted\"", "values", List.of(1, 2)))) {
            String wire = JSON.toJSONString(Map.of("secretResolutions", shared,
                    "environmentVariables", shared, "copies", List.of(shared, shared),
                    "memoryStartingRevisions", Map.of(10002L, 2L)));
            var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(wire);
            assertFalse(wire.contains("$ref"));
            assertEquals(parsed.get("secretResolutions"), parsed.get("environmentVariables"));
            assertEquals(parsed.get("secretResolutions"), parsed.get("copies").get(1));
            assertEquals(2, parsed.get("memoryStartingRevisions").get("10002").asInt());
        }
    }

    @Test
    void typeAndReferenceLookingInputStaysLiteralData() {
        String input = "{\"@type\":\"java.lang.Exception\",\"$ref\":\"$.other\","
                + "\"text\":\"中文\\n\\\"quoted\\\"\",\"10002\":2}";
        JSONObject parsed = JSON.parseObject(input);
        assertEquals("java.lang.Exception", parsed.getString("@type"));
        assertEquals("$.other", parsed.getString("$ref"));
        assertEquals("中文\n\"quoted\"", parsed.getString("text"));
        assertEquals(parsed, JSON.parseObject(JSON.toJSONString(parsed)));
    }

    record Sample(String name) {}
}
