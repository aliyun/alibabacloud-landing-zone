package com.aliyun.autowonder.integration;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.integration.common.ExternalPrincipalDO;
import com.aliyun.autowonder.integration.common.ExternalPrincipalDao;
import com.aliyun.autowonder.integration.common.PrincipalRelationSnapshot;
import com.aliyun.autowonder.integration.provider.ExternalPrincipalRef;
import com.aliyun.autowonder.integration.provider.ExternalPrincipalRelation;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemDetail;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class ExternalPrincipalService {

    private final ExternalPrincipalDao principalDao;

    public ExternalPrincipalService(ExternalPrincipalDao principalDao) {
        this.principalDao = principalDao;
    }

    public IdentitySnapshot resolveWorkitem(String provider, ExternalWorkitemDetail detail) {
        Long reporterId = upsert(provider, detail.getReporter());
        Long businessOwnerId = upsert(provider, detail.getBusinessOwner());
        List<PrincipalRelationSnapshot> relations = new ArrayList<>();
        if (detail.getPrincipalRelations() != null) {
            for (ExternalPrincipalRelation relation : detail.getPrincipalRelations()) {
                PrincipalRelationSnapshot snapshot = resolveRelation(provider, relation);
                if (snapshot != null && !snapshot.getPrincipalIds().isEmpty()) {
                    relations.add(snapshot);
                }
            }
        }
        String relationsJson = relations.isEmpty() ? null : JSON.toJSONString(relations);
        return new IdentitySnapshot(reporterId, businessOwnerId, relationsJson);
    }

    public Long upsert(String provider, ExternalPrincipalRef reference) {
        if (reference == null || reference.getSubjectId() == null || reference.getSubjectId().isBlank()) {
            return null;
        }
        ExternalPrincipalDO principal = new ExternalPrincipalDO();
        principal.setProvider(provider);
        principal.setSubjectId(reference.getSubjectId());
        principal.setDisplayName(reference.getDisplayName());
        principalDao.upsert(principal);
        ExternalPrincipalDO stored = principalDao.findBySource(provider, principal.getSubjectId());
        if (stored == null || stored.getId() == null) {
            throw new IllegalStateException("external principal upsert did not return a stored principal");
        }
        return stored.getId();
    }

    private PrincipalRelationSnapshot resolveRelation(String provider,
                                                       ExternalPrincipalRelation relation) {
        if (relation == null || relation.getSourceKey() == null || relation.getSourceKey().isBlank()) {
            return null;
        }
        LinkedHashSet<Long> principalIds = new LinkedHashSet<>();
        if (relation.getPrincipals() != null) {
            for (ExternalPrincipalRef reference : relation.getPrincipals()) {
                Long principalId = upsert(provider, reference);
                if (principalId != null) {
                    principalIds.add(principalId);
                }
            }
        }
        PrincipalRelationSnapshot snapshot = new PrincipalRelationSnapshot();
        snapshot.setSourceKey(relation.getSourceKey());
        snapshot.setDisplayName(relation.getDisplayName());
        snapshot.setPrincipalIds(new ArrayList<>(principalIds));
        return snapshot;
    }

    public record IdentitySnapshot(Long reporterPrincipalId,
                                   Long businessOwnerPrincipalId,
                                   String principalRelationsJson) {
    }
}
