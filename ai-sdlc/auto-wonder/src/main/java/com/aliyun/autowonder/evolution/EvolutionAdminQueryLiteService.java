package com.aliyun.autowonder.evolution;

import org.springframework.stereotype.Service;

@Service
public class EvolutionAdminQueryLiteService {

    private final EvolutionProposalDao proposalDao;
    private final BayesianEvidenceDao evidenceDao;

    public EvolutionAdminQueryLiteService(EvolutionProposalDao proposalDao,
                                          BayesianEvidenceDao evidenceDao) {
        this.proposalDao = proposalDao;
        this.evidenceDao = evidenceDao;
    }

    public EvolutionAdminOverviewVO overview(long tenantId, Integer limit) {
        int bounded = Math.min(Math.max(limit == null ? 20 : limit, 1), 20);
        EvolutionAdminOverviewVO vo = new EvolutionAdminOverviewVO();
        vo.setProposals(proposalDao.listRecent(tenantId, bounded));
        vo.setEvidence(evidenceDao.listRecent(tenantId, bounded));
        return vo;
    }
}
