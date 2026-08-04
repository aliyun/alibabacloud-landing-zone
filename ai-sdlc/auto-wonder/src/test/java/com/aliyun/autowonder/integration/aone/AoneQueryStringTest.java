package com.aliyun.autowonder.integration.aone;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AoneQueryStringTest {

    @Test
    void serializesAoneListParamsAsJsonArrayStrings() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("akProjectId", "2161074");
        params.put("idList", List.of(84189105L, 84189109L));
        params.put("stamp", "Req,Bug,Task");
        params.put("empty", List.of());
        params.put("missing", null);

        String query = AoneQueryString.toQuery(params);

        assertEquals("akProjectId=2161074&idList=[84189105,84189109]&stamp=Req,Bug,Task", query);
    }
}
