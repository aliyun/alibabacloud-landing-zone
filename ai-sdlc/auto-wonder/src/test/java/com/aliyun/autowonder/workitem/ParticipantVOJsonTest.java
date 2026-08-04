package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticipantVOJsonTest {

    @Test
    void serializesAgentFlagUsingFrontendContractName() {
        ParticipantVO participant = new ParticipantVO();
        participant.setAgent(true);

        JsonNode json = new ObjectMapper().valueToTree(participant);

        assertTrue(json.path("isAgent").asBoolean());
        assertFalse(json.has("agent"));
    }
}
