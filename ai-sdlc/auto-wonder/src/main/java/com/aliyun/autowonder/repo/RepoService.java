package com.aliyun.autowonder.repo;

import com.aliyun.autowonder.agent.AgentRepoPermDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.repo.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class RepoService {

    private final RepoDao repoDao;
    private final RepoConclusionDao conclusionDao;
    private final RepoRelationDao relationDao;
    private final AgentRepoPermDao agentRepoPermDao;
    private final RepoConnectionTester connectionTester;

    public RepoService(RepoDao repoDao, RepoConclusionDao conclusionDao,
                       RepoRelationDao relationDao, AgentRepoPermDao agentRepoPermDao,
                       RepoConnectionTester connectionTester) {
        this.repoDao = repoDao;
        this.conclusionDao = conclusionDao;
        this.relationDao = relationDao;
        this.agentRepoPermDao = agentRepoPermDao;
        this.connectionTester = connectionTester;
    }

    public RepoVO create(CreateRepoRequest req, long tenantId, long userId) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.REPO_NAME_REQUIRED);
        }
        if (req.getUrl() == null || req.getUrl().isBlank()) {
            throw new BizException(ErrorCode.REPO_URL_REQUIRED);
        }
        RepoDO repo = new RepoDO();
        repo.setTenantId(tenantId);
        repo.setName(req.getName().trim());
        repo.setUrl(req.getUrl().trim());
        repo.setDefaultBranch(req.getDefaultBranch());
        repo.setDescription(req.getDescription());
        repo.setScanStatus("UNSCANNED");
        repo.setCreatorId(userId);
        repo.setVersion(0);
        repoDao.insert(repo);
        return toVO(repo);
    }

    public RepoVO get(long id) {
        RepoDO repo = repoDao.findById(id);
        if (repo == null) {
            throw new BizException(ErrorCode.REPO_NOT_FOUND);
        }
        return toVO(repo);
    }

    public RepoVO get(long id, long tenantId) {
        RepoDO repo = repoDao.findById(id);
        if (repo == null || repo.getTenantId() == null || repo.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.REPO_NOT_FOUND);
        }
        return toVO(repo);
    }

    public List<RepoVO> list(long tenantId, int page, int size) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), 100);
        int offset = (p - 1) * sz;
        List<RepoVO> result = new ArrayList<>();
        for (RepoDO repo : repoDao.list(tenantId, offset, sz)) {
            result.add(toVO(repo));
        }
        return result;
    }

    public RepoVO update(long id, UpdateRepoRequest req, long tenantId, long userId) {
        RepoDO repo = repoDao.findById(id);
        if (repo == null) {
            throw new BizException(ErrorCode.REPO_NOT_FOUND);
        }
        String name = requiredField(req.isNamePresent(), req.getName(), repo.getName(),
                ErrorCode.REPO_NAME_REQUIRED);
        String url = requiredField(req.isUrlPresent(), req.getUrl(), repo.getUrl(),
                ErrorCode.REPO_URL_REQUIRED);
        String defaultBranch = optionalField(req.isDefaultBranchPresent(),
                req.getDefaultBranch(), repo.getDefaultBranch());
        String description = optionalField(req.isDescriptionPresent(),
                req.getDescription(), repo.getDescription());
        int rows = repoDao.update(id, tenantId, name, url, defaultBranch,
                description, repo.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.REPO_VERSION_CONFLICT);
        }
        return get(id);
    }

    private String requiredField(boolean present, String value, String current, ErrorCode missing) {
        if (!present) {
            return current;
        }
        if (value == null || value.isBlank()) {
            throw new BizException(missing);
        }
        return value.trim();
    }

    private String optionalField(boolean present, String value, String current) {
        return present ? value : current;
    }

    @Transactional
    public void delete(long id, long tenantId, long userId) {
        RepoDO repo = repoDao.findById(id);
        if (repo == null) {
            throw new BizException(ErrorCode.REPO_NOT_FOUND);
        }
        if (agentRepoPermDao.countByRepoId(id, tenantId) > 0) {
            throw new BizException(ErrorCode.REPO_DELETE_IN_USE);
        }
        int rows = repoDao.softDelete(id, tenantId, repo.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.REPO_VERSION_CONFLICT);
        }
        conclusionDao.deleteByRepoId(id, tenantId);
        relationDao.deleteByRepoId(id, tenantId);
    }

    public void startScan(long id, long tenantId, long userId) {
        RepoDO repo = repoDao.findById(id);
        if (repo == null) {
            throw new BizException(ErrorCode.REPO_NOT_FOUND);
        }
        repoDao.updateScanStatus(id, tenantId, "SCANNING", repo.getVersion(), userId);
    }

    public RepoConnectionTestResult testConnection(TestRepoConnectionRequest req) {
        return connectionTester.test(req);
    }

    public RepoConclusionVO getConclusion(long repoId) {
        RepoDO repo = repoDao.findById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.REPO_NOT_FOUND);
        }
        RepoConclusionDO c = conclusionDao.findByRepoId(repoId);
        if (c == null) {
            return null;
        }
        return toConclusionVO(c);
    }

    public RepoConclusionVO updateConclusion(long repoId, UpdateConclusionRequest req,
                                              long tenantId, long userId) {
        RepoDO repo = repoDao.findById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.REPO_NOT_FOUND);
        }
        RepoConclusionDO existing = conclusionDao.findByRepoId(repoId);
        if (existing == null) {
            RepoConclusionDO c = new RepoConclusionDO();
            c.setTenantId(tenantId);
            c.setRepoId(repoId);
            c.setPurpose(req.getPurpose());
            c.setKeyBusiness(req.getKeyBusiness());
            c.setUpstreams(req.getUpstreams());
            c.setDownstreams(req.getDownstreams());
            c.setSummaryMd(req.getSummaryMd());
            c.setCreatorId(userId);
            c.setVersion(0);
            conclusionDao.insert(c);
            return toConclusionVO(c);
        } else {
            int rows = conclusionDao.update(existing.getId(), tenantId,
                    req.getPurpose(), req.getKeyBusiness(),
                    req.getUpstreams(), req.getDownstreams(),
                    req.getSummaryMd(), existing.getVersion(), userId);
            if (rows == 0) {
                throw new BizException(ErrorCode.REPO_VERSION_CONFLICT);
            }
            RepoConclusionDO updated = conclusionDao.findByRepoId(repoId);
            return toConclusionVO(updated);
        }
    }

    public List<RepoRelationVO> listRelationsByRepoId(long tenantId, long repoId) {
        List<RepoRelationVO> result = new ArrayList<>();
        for (RepoRelationDO r : relationDao.listByRepoId(tenantId, repoId)) {
            result.add(toRelationVO(r));
        }
        return result;
    }

    public List<RepoRelationVO> listRelations(long tenantId) {
        List<RepoRelationVO> result = new ArrayList<>();
        for (RepoRelationDO r : relationDao.listByTenantId(tenantId)) {
            result.add(toRelationVO(r));
        }
        return result;
    }

    @Transactional
    public RepoRelationVO createRelation(CreateRelationRequest req, long tenantId, long userId) {
        if (req.getFromRepoId().equals(req.getToRepoId())) {
            throw new BizException(ErrorCode.REPO_RELATION_SELF_REF);
        }
        RepoDO fromRepo = repoDao.findById(req.getFromRepoId());
        if (fromRepo == null || fromRepo.getTenantId() == null || fromRepo.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.REPO_NOT_FOUND);
        }
        RepoDO toRepo = repoDao.findById(req.getToRepoId());
        if (toRepo == null || toRepo.getTenantId() == null || toRepo.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.REPO_NOT_FOUND);
        }
        RepoRelationDO existing = relationDao.findByUkIncludeDeleted(tenantId, req.getFromRepoId(),
                req.getToRepoId(), req.getRelationType());
        if (existing != null) {
            if (existing.getIsDeleted() == 0) {
                throw new BizException(ErrorCode.REPO_RELATION_DUPLICATE);
            }
            relationDao.undelete(existing.getId(), tenantId,
                    req.getDescription(), req.getAiSessionId(), userId);
            RepoRelationDO restored = relationDao.findById(existing.getId());
            if (restored == null) {
                throw new BizException(ErrorCode.REPO_RELATION_NOT_FOUND);
            }
            return toRelationVO(restored);
        }
        RepoRelationDO relation = new RepoRelationDO();
        relation.setTenantId(tenantId);
        relation.setFromRepoId(req.getFromRepoId());
        relation.setToRepoId(req.getToRepoId());
        relation.setRelationType(req.getRelationType());
        relation.setDescription(req.getDescription());
        relation.setAiSessionId(req.getAiSessionId());
        relation.setCreatorId(userId);
        relationDao.insert(relation);
        return toRelationVO(relation);
    }

    public void deleteRelation(long relationId, long tenantId) {
        RepoRelationDO relation = relationDao.findById(relationId);
        if (relation == null || relation.getTenantId() == null || relation.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.REPO_RELATION_NOT_FOUND);
        }
        relationDao.softDelete(relationId, tenantId);
    }

    private RepoVO toVO(RepoDO repo) {
        RepoVO vo = new RepoVO();
        vo.setId(repo.getId());
        vo.setName(repo.getName());
        vo.setUrl(repo.getUrl());
        vo.setDefaultBranch(repo.getDefaultBranch());
        vo.setDescription(repo.getDescription());
        vo.setScanStatus(repo.getScanStatus());
        vo.setVersion(repo.getVersion());
        vo.setGmtCreate(repo.getGmtCreate());
        return vo;
    }

    private RepoConclusionVO toConclusionVO(RepoConclusionDO c) {
        RepoConclusionVO vo = new RepoConclusionVO();
        vo.setId(c.getId());
        vo.setRepoId(c.getRepoId());
        vo.setPurpose(c.getPurpose());
        vo.setKeyBusiness(c.getKeyBusiness());
        vo.setUpstreams(c.getUpstreams());
        vo.setDownstreams(c.getDownstreams());
        vo.setSummaryMd(c.getSummaryMd());
        vo.setAiSessionId(c.getAiSessionId());
        vo.setVersion(c.getVersion());
        vo.setGmtCreate(c.getGmtCreate());
        return vo;
    }

    private RepoRelationVO toRelationVO(RepoRelationDO r) {
        RepoRelationVO vo = new RepoRelationVO();
        vo.setId(r.getId());
        vo.setFromRepoId(r.getFromRepoId());
        vo.setToRepoId(r.getToRepoId());
        vo.setRelationType(r.getRelationType());
        vo.setDescription(r.getDescription());
        vo.setAiSessionId(r.getAiSessionId());
        vo.setGmtCreate(r.getGmtCreate());
        return vo;
    }
}
