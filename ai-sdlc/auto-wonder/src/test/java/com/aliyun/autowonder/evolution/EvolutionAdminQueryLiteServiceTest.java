package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionAdminQueryLiteServiceTest {

    private EvolutionProposalDao proposalDao;
    private BayesianEvidenceDao evidenceDao;
    private EvolutionAdminQueryLiteService service;

    @BeforeEach
    void setUp() {
        proposalDao = mock(EvolutionProposalDao.class);
        evidenceDao = mock(BayesianEvidenceDao.class);
        service = new EvolutionAdminQueryLiteService(proposalDao, evidenceDao);
    }

    @Test
    void returnsRecentEvolutionOverviewWithBoundedLimit() {
        when(proposalDao.listRecent(1L, 20)).thenReturn(List.of(new EvolutionProposalDO()));
        when(evidenceDao.listRecent(1L, 20)).thenReturn(List.of(new BayesianEvidenceDO()));

        EvolutionAdminOverviewVO overview = service.overview(1L, 500);

        assertEquals(1, overview.getProposals().size());
        assertEquals(1, overview.getEvidence().size());
        verify(proposalDao).listRecent(1L, 20);
    }
}
