package com.aliyun.autowonder.integration;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.integration.common.ExternalPrincipalDO;
import com.aliyun.autowonder.integration.common.ExternalPrincipalDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.common.PrincipalRelationSnapshot;
import com.aliyun.autowonder.workitem.dto.ExternalCollaborationVO;
import com.aliyun.autowonder.workitem.dto.ExternalPrincipalRelationVO;
import com.aliyun.autowonder.workitem.dto.ExternalPrincipalVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ExternalWorkitemViewService {

    private final ExternalWorkitemLinkDao linkDao;
    private final ExternalPrincipalDao principalDao;

    public ExternalWorkitemViewService(ExternalWorkitemLinkDao linkDao, ExternalPrincipalDao principalDao) {
        this.linkDao = linkDao;
        this.principalDao = principalDao;
    }

    public ExternalCollaborationVO find(long tenantId, long workitemId) {
        List<ExternalWorkitemLinkDO> links = linkDao.listByWorkitem(tenantId, workitemId);
        if (links == null || links.isEmpty()) {
            return null;
        }
        ExternalWorkitemLinkDO link = links.stream()
                .filter(item -> item != null)
                .min(Comparator
                        .comparing((ExternalWorkitemLinkDO item) -> !"AONE".equals(item.getProvider()))
                        .thenComparing(ExternalWorkitemLinkDO::getGmtCreate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ExternalWorkitemLinkDO::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        if (link == null) {
            return null;
        }

        List<PrincipalRelationSnapshot> snapshots = parseRelations(link.getPrincipalRelationsJson());
        Set<Long> principalIds = new LinkedHashSet<>();
        add(principalIds, link.getReporterPrincipalId());
        add(principalIds, link.getBusinessOwnerPrincipalId());
        for (PrincipalRelationSnapshot snapshot : snapshots) {
            if (snapshot.getPrincipalIds() != null) {
                snapshot.getPrincipalIds().forEach(id -> add(principalIds, id));
            }
        }
        Map<Long, ExternalPrincipalDO> principalById = loadPrincipals(principalIds);

        ExternalCollaborationVO view = new ExternalCollaborationVO();
        view.setProvider(link.getProvider());
        view.setExternalProjectId(link.getExternalProjectId());
        view.setExternalWorkitemId(link.getExternalWorkitemId());
        view.setExternalUrl(link.getExternalUrl());
        view.setSourceStatusId(link.getSourceStatusId());
        view.setSourceStatusName(link.getSourceStatusName());
        view.setSourceLifecycle(link.getSourceLifecycle());
        view.setReporter(toPrincipal(principalById.get(link.getReporterPrincipalId())));
        view.setBusinessOwner(toPrincipal(principalById.get(link.getBusinessOwnerPrincipalId())));
        view.setPrincipalRelations(toRelationViews(snapshots, principalById));
        view.setLastSyncAt(link.getLastSyncAt());
        view.setSyncStatus(link.getSyncStatus());
        view.setLastErrorCode(link.getLastErrorCode());
        view.setLastError(link.getLastError());
        return view;
    }

    public String principalName(Long principalId) {
        ExternalPrincipalDO principal = findPrincipal(principalId);
        if (principal == null) {
            return null;
        }
        if (principal.getDisplayName() != null && !principal.getDisplayName().isBlank()) {
            return principal.getDisplayName();
        }
        if ("AONE".equals(principal.getProvider())) {
            return "Aone 用户（staffId: " + principal.getSubjectId() + "）";
        }
        if (principal.getProvider() != null && !principal.getProvider().isBlank()) {
            return principal.getProvider() + " 用户（ID: " + principal.getSubjectId() + "）";
        }
        return principal.getSubjectId();
    }

    public String principalDisplayName(Long principalId) {
        ExternalPrincipalDO principal = findPrincipal(principalId);
        if (principal == null) {
            return null;
        }
        if (principal.getDisplayName() == null || principal.getDisplayName().isBlank()) {
            return principalName(principalId);
        }
        String provider = "AONE".equals(principal.getProvider()) ? "Aone" : principal.getProvider();
        if (provider == null || provider.isBlank()) {
            return principal.getDisplayName() + "（" + principal.getSubjectId() + "）";
        }
        return principal.getDisplayName() + "（" + provider + " · " + principal.getSubjectId() + "）";
    }

    /** Concise source identity for business fields, consistent with the external collaboration card. */
    public String principalCompactDisplayName(Long principalId) {
        ExternalPrincipalDO principal = findPrincipal(principalId);
        if (principal == null) {
            return null;
        }
        if (principal.getDisplayName() != null && !principal.getDisplayName().isBlank()
                && principal.getSubjectId() != null && !principal.getSubjectId().isBlank()) {
            return principal.getDisplayName() + "（" + principal.getSubjectId() + "）";
        }
        return principalName(principalId);
    }

    /**
     * Resolves source-side creators for a page of imported workitems in one principal lookup.
     * The local workitem creator remains the importer for audit, while callers use this view for
     * human-facing source attribution.
     */
    public Map<Long, ExternalPrincipalVO> reportersByWorkitem(Collection<ExternalWorkitemLinkDO> links) {
        if (links == null || links.isEmpty()) {
            return Map.of();
        }
        Map<Long, ExternalWorkitemLinkDO> preferredByWorkitem = new LinkedHashMap<>();
        for (ExternalWorkitemLinkDO candidate : links) {
            if (candidate == null || candidate.getWorkitemId() == null) {
                continue;
            }
            preferredByWorkitem.merge(candidate.getWorkitemId(), candidate, this::preferLink);
        }
        Set<Long> principalIds = new LinkedHashSet<>();
        for (ExternalWorkitemLinkDO link : preferredByWorkitem.values()) {
            add(principalIds, link.getReporterPrincipalId());
        }
        Map<Long, ExternalPrincipalDO> principals = loadPrincipals(principalIds);
        Map<Long, ExternalPrincipalVO> result = new LinkedHashMap<>();
        for (Map.Entry<Long, ExternalWorkitemLinkDO> entry : preferredByWorkitem.entrySet()) {
            ExternalPrincipalVO reporter = toPrincipal(principals.get(entry.getValue().getReporterPrincipalId()));
            if (reporter != null) {
                result.put(entry.getKey(), reporter);
            }
        }
        return result;
    }

    private ExternalWorkitemLinkDO preferLink(ExternalWorkitemLinkDO first, ExternalWorkitemLinkDO second) {
        boolean firstAone = "AONE".equals(first.getProvider());
        boolean secondAone = "AONE".equals(second.getProvider());
        if (firstAone != secondAone) {
            return firstAone ? first : second;
        }
        return Comparator
                .comparing(ExternalWorkitemLinkDO::getGmtCreate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ExternalWorkitemLinkDO::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                .compare(first, second) <= 0 ? first : second;
    }

    private ExternalPrincipalDO findPrincipal(Long principalId) {
        return principalId == null ? null : principalDao.findById(principalId);
    }

    private List<PrincipalRelationSnapshot> parseRelations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<PrincipalRelationSnapshot> result = JSON.parseArray(json, PrincipalRelationSnapshot.class);
            return result == null ? List.of() : result;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private Map<Long, ExternalPrincipalDO> loadPrincipals(Set<Long> principalIds) {
        if (principalIds.isEmpty()) {
            return Map.of();
        }
        List<ExternalPrincipalDO> principals = principalDao.listByIds(principalIds);
        Map<Long, ExternalPrincipalDO> result = new LinkedHashMap<>();
        if (principals != null) {
            for (ExternalPrincipalDO principal : principals) {
                if (principal != null && principal.getId() != null) {
                    result.put(principal.getId(), principal);
                }
            }
        }
        return result;
    }

    private List<ExternalPrincipalRelationVO> toRelationViews(
            List<PrincipalRelationSnapshot> snapshots, Map<Long, ExternalPrincipalDO> principalById) {
        List<ExternalPrincipalRelationVO> result = new ArrayList<>();
        for (PrincipalRelationSnapshot snapshot : snapshots) {
            ExternalPrincipalRelationVO relation = new ExternalPrincipalRelationVO();
            relation.setSourceKey(snapshot.getSourceKey());
            relation.setDisplayName(snapshot.getDisplayName());
            List<ExternalPrincipalVO> principals = new ArrayList<>();
            if (snapshot.getPrincipalIds() != null) {
                for (Long principalId : snapshot.getPrincipalIds()) {
                    ExternalPrincipalVO principal = toPrincipal(principalById.get(principalId));
                    if (principal != null) {
                        principals.add(principal);
                    }
                }
            }
            if (!principals.isEmpty()) {
                relation.setPrincipals(principals);
                result.add(relation);
            }
        }
        return result;
    }

    private ExternalPrincipalVO toPrincipal(ExternalPrincipalDO principal) {
        if (principal == null) {
            return null;
        }
        ExternalPrincipalVO view = new ExternalPrincipalVO();
        view.setId(principal.getId());
        view.setProvider(principal.getProvider());
        view.setSubjectId(principal.getSubjectId());
        view.setDisplayName(principal.getDisplayName());
        return view;
    }

    private void add(Set<Long> values, Long value) {
        if (value != null) {
            values.add(value);
        }
    }
}
