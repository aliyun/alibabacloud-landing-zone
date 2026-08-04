package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvolutionProposalLifecycleJsonTest {

    @Test
    void stageJsonIsStoredInOneLifecycleJsonColumn() {
        EvolutionProposalDO proposal = new EvolutionProposalDO();

        proposal.setReplayJson("{\"verdict\":\"PASS\"}");
        proposal.setGateJson("{\"BENCHMARK\":{\"verdict\":\"PASS\"}}");
        proposal.setReleaseJson("{\"assetId\":301}");

        assertEquals("{\"verdict\":\"PASS\"}", proposal.getReplayJson());
        assertEquals("{\"BENCHMARK\":{\"verdict\":\"PASS\"}}", proposal.getGateJson());
        assertEquals("{\"assetId\":301}", proposal.getReleaseJson());
        assertTrue(proposal.getLifecycleJson().contains("\"replay\""));
        assertTrue(proposal.getLifecycleJson().contains("\"gates\""));
        assertTrue(proposal.getLifecycleJson().contains("\"release\""));
    }
}
