package com.aliyun.autowonder.repo.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateRepoRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesPresentFieldsAndExplicitNulls() throws Exception {
        UpdateRepoRequest req = UpdateRepoRequest.fromJson(
                mapper.readTree("{\"description\":\"new desc\",\"defaultBranch\":null}"));

        assertTrue(req.isDescriptionPresent());
        assertEquals("new desc", req.getDescription());
        assertTrue(req.isDefaultBranchPresent());
        assertNull(req.getDefaultBranch());
        assertFalse(req.isNamePresent());
        assertFalse(req.isUrlPresent());
    }

    @Test
    void emptyBodyLeavesEveryFieldAbsent() throws Exception {
        UpdateRepoRequest req = UpdateRepoRequest.fromJson(mapper.readTree("{}"));

        assertFalse(req.isNamePresent());
        assertFalse(req.isUrlPresent());
        assertFalse(req.isDefaultBranchPresent());
        assertFalse(req.isDescriptionPresent());
    }
}
