package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.dto.DispatchPageVO;
import com.aliyun.autowonder.dispatch.dto.DispatchVO;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DispatchQueryService {

    private final DispatchDao dispatchDao;
    private final WorkitemDao workitemDao;
    private final AgentDao agentDao;
    private final AgentVersionDao agentVersionDao;
    private final ExecutorDao executorDao;
    private final ArtifactService artifactService;

    public DispatchQueryService(DispatchDao dispatchDao, WorkitemDao workitemDao, AgentDao agentDao,
                                AgentVersionDao agentVersionDao, ExecutorDao executorDao,
                                ArtifactService artifactService) {
        this.dispatchDao = dispatchDao;
        this.workitemDao = workitemDao;
        this.agentDao = agentDao;
        this.agentVersionDao = agentVersionDao;
        this.executorDao = executorDao;
        this.artifactService = artifactService;
    }

    public DispatchPageVO list(long tenantId, String status, Long agentId, Long workitemId,
                               String timeRange, int page, int pageSize) {
        Date since = computeSince(timeRange);
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        int offset = (safePage - 1) * safePageSize;
        List<DispatchDO> rows = dispatchDao.listByTenant(tenantId, emptyToNull(status),
                agentId, workitemId, since, safePageSize, offset);
        long total = dispatchDao.countByTenant(tenantId, emptyToNull(status), agentId, workitemId, since);
        List<DispatchVO> list = enrich(tenantId, rows);
        return new DispatchPageVO(list, total, safePage, safePageSize);
    }

    public DispatchVO get(long tenantId, long id) {
        DispatchDO d = dispatchDao.findById(id);
        if (d == null || d.getTenantId() == null || d.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        }
        DispatchVO vo = enrich(tenantId, List.of(d)).get(0);
        vo.setArtifacts(artifactService.listByDispatch(id, tenantId));
        return vo;
    }

    private List<DispatchVO> enrich(long tenantId, List<DispatchDO> rows) {
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, String> titles = index(collectIds(rows.stream()
                        .filter(d -> d.executionSourceType() == ExecutionSourceType.WORKITEM)
                        .collect(Collectors.toList()), DispatchDO::getWorkitemId),
                ids -> workitemDao.listByIds(tenantId, ids), WorkitemDO::getId, WorkitemDO::getTitle);
        Map<Long, String> agentNames = index(collectIds(rows, DispatchDO::getAgentId),
                ids -> agentDao.listByIds(tenantId, ids), AgentDO::getId, AgentDO::getName);
        Map<Long, Integer> versionNos = index(collectIds(rows, DispatchDO::getAgentVersionId),
                ids -> agentVersionDao.listByIds(tenantId, ids), AgentVersionDO::getId, AgentVersionDO::getVersionNo);
        Map<Long, String> executorNames = index(collectIds(rows, DispatchDO::getExecutorId),
                ids -> executorDao.listByIds(tenantId, ids), ExecutorDO::getId, ExecutorDO::getName);

        List<DispatchVO> out = new ArrayList<>(rows.size());
        for (DispatchDO d : rows) {
            DispatchVO vo = new DispatchVO();
            vo.setId(d.getId());
            vo.setSourceType(d.executionSourceType().name());
            vo.setWorkitemId(d.getWorkitemId());
            vo.setSdlcStepId(d.getSdlcStepId());
            vo.setAgentId(d.getAgentId());
            vo.setAgentVersionId(d.getAgentVersionId());
            vo.setExecutorId(d.getExecutorId());
            vo.setStatus(d.getStatus());
            vo.setAttempt(d.getAttempt());
            vo.setResultSummary(d.getResultSummary());
            vo.setError(d.getError());
            vo.setPackageOssRef(d.getPackageOssRef());
            vo.setGmtCreate(d.getGmtCreate());
            vo.setGmtModified(d.getGmtModified());
            vo.setWorkitemTitle(d.getWorkitemId() == null
                    || d.executionSourceType() != ExecutionSourceType.WORKITEM
                    ? null : titles.get(d.getWorkitemId()));
            vo.setAgentName(d.getAgentId() == null ? null : agentNames.get(d.getAgentId()));
            vo.setAgentVersionNo(d.getAgentVersionId() == null ? null : versionNos.get(d.getAgentVersionId()));
            vo.setExecutorName(d.getExecutorId() == null ? null : executorNames.get(d.getExecutorId()));
            out.add(vo);
        }
        return out;
    }

    private Set<Long> collectIds(List<DispatchDO> rows, Function<DispatchDO, Long> getter) {
        Set<Long> ids = new LinkedHashSet<>();
        for (DispatchDO d : rows) {
            Long v = getter.apply(d);
            if (v != null) {
                ids.add(v);
            }
        }
        return ids;
    }

    private <E, V> Map<Long, V> index(Set<Long> ids,
                                      Function<Set<Long>, List<E>> loader,
                                      Function<E, Long> keyFn,
                                      Function<E, V> valFn) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return loader.apply(ids).stream()
                .filter(e -> valFn.apply(e) != null)
                .collect(Collectors.toMap(keyFn, valFn, (a, b) -> a));
    }

    private String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private Date computeSince(String timeRange) {
        Calendar cal = Calendar.getInstance();
        switch (timeRange == null ? "30d" : timeRange) {
            case "7d":
                cal.add(Calendar.DAY_OF_MONTH, -7);
                break;
            case "90d":
                cal.add(Calendar.DAY_OF_MONTH, -90);
                break;
            default:
                cal.add(Calendar.DAY_OF_MONTH, -30);
                break;
        }
        return cal.getTime();
    }
}
