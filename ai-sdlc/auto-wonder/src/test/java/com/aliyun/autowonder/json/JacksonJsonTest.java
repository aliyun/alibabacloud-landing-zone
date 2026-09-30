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

    record Sample(String name) {}
}
