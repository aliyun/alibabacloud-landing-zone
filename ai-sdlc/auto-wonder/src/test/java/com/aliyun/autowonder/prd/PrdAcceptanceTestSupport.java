package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fakes shared by the acceptance and freeze-service tests.
 *
 * <p>These mirror real MyBatis behaviour on the two points that matter for the tests: reads return a
 * detached copy (an update never mutates the object a caller already holds), and {@code insertIgnore}
 * is a no-op on a duplicate natural key. Faking them as live-object maps would hide exactly the
 * drift the seeding and report paths are meant to prevent.
 */
final class PrdAcceptanceTestSupport {

    private PrdAcceptanceTestSupport() {
    }

    static final class InMemoryAcceptanceDao implements PrdRequirementAcceptanceDao {
        final Map<Long, PrdRequirementAcceptanceDO> rows = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(20000);

        @Override public int insertIgnore(PrdRequirementAcceptanceDO row) {
            // Mirror the schema's NOT NULL columns so a caller that forgets one fails here, exactly as
            // it would against MySQL, instead of silently persisting a row a real database rejects.
            if (row.getWorkspaceId() == null || row.getWorkitemId() == null
                    || row.getDispatchId() == null || row.getDesignVersionId() == null
                    || row.getRequirementKey() == null) {
                throw new IllegalArgumentException("acceptance row has a NOT NULL column unset");
            }
            boolean exists = rows.values().stream().anyMatch(r ->
                    r.getWorkspaceId().equals(row.getWorkspaceId())
                            && r.getDispatchId().equals(row.getDispatchId())
                            && r.getRequirementKey().equals(row.getRequirementKey()));
            if (exists) {
                return 0;
            }
            PrdRequirementAcceptanceDO copy = copy(row);
            copy.setId(ids.incrementAndGet());
            copy.setVersion(0);
            copy.setGmtCreate(new Date());
            rows.put(copy.getId(), copy);
            return 1;
        }

        @Override public List<PrdRequirementAcceptanceDO> listByDispatch(Long workspaceId, Long dispatchId) {
            List<PrdRequirementAcceptanceDO> out = new ArrayList<>();
            rows.values().stream()
                    .filter(r -> r.getWorkspaceId().equals(workspaceId) && r.getDispatchId().equals(dispatchId))
                    .sorted((a, b) -> a.getRequirementKey().compareTo(b.getRequirementKey()))
                    .forEach(r -> out.add(copy(r)));
            return out;
        }

        @Override public PrdRequirementAcceptanceDO findByDispatchAndKey(Long workspaceId, Long dispatchId,
                                                                         String requirementKey) {
            return rows.values().stream()
                    .filter(r -> r.getWorkspaceId().equals(workspaceId) && r.getDispatchId().equals(dispatchId)
                            && r.getRequirementKey().equals(requirementKey))
                    .findFirst().map(InMemoryAcceptanceDao::copy).orElse(null);
        }

        @Override public int updateReport(PrdRequirementAcceptanceDO patch) {
            PrdRequirementAcceptanceDO existing = rows.get(patch.getId());
            if (existing == null || !existing.getWorkspaceId().equals(patch.getWorkspaceId())
                    || !existing.getDispatchId().equals(patch.getDispatchId())) {
                return 0;
            }
            existing.setImplementation(patch.getImplementation());
            existing.setVerdict(patch.getVerdict());
            existing.setNote(patch.getNote());
            existing.setNotApplicableReason(patch.getNotApplicableReason());
            existing.setEvidenceJson(patch.getEvidenceJson());
            existing.setReportedAt(patch.getReportedAt());
            existing.setModifierId(patch.getModifierId());
            existing.setVersion(existing.getVersion() + 1);
            return 1;
        }

        @Override public List<PrdRequirementAcceptanceDO> listByWorkitem(Long workspaceId, Long workitemId) {
            List<PrdRequirementAcceptanceDO> out = new ArrayList<>();
            rows.values().stream()
                    .filter(r -> r.getWorkspaceId().equals(workspaceId) && workitemId.equals(r.getWorkitemId()))
                    .forEach(r -> out.add(copy(r)));
            return out;
        }

        @Override public int deleteByWorkitem(Long workspaceId, Long workitemId) {
            List<Long> doomed = rows.values().stream()
                    .filter(r -> r.getWorkspaceId().equals(workspaceId) && workitemId.equals(r.getWorkitemId()))
                    .map(PrdRequirementAcceptanceDO::getId).toList();
            doomed.forEach(rows::remove);
            return doomed.size();
        }

        int size() {
            return rows.size();
        }

        static PrdRequirementAcceptanceDO copy(PrdRequirementAcceptanceDO src) {
            PrdRequirementAcceptanceDO d = new PrdRequirementAcceptanceDO();
            d.setId(src.getId());
            d.setWorkspaceId(src.getWorkspaceId());
            d.setWorkitemId(src.getWorkitemId());
            d.setDispatchId(src.getDispatchId());
            d.setDesignVersionId(src.getDesignVersionId());
            d.setRequirementKey(src.getRequirementKey());
            d.setImplementation(src.getImplementation());
            d.setVerdict(src.getVerdict());
            d.setNote(src.getNote());
            d.setNotApplicableReason(src.getNotApplicableReason());
            d.setEvidenceJson(src.getEvidenceJson());
            d.setReportedAt(src.getReportedAt());
            d.setVersion(src.getVersion());
            d.setCreatorId(src.getCreatorId());
            d.setModifierId(src.getModifierId());
            d.setGmtCreate(src.getGmtCreate());
            d.setGmtModified(src.getGmtModified());
            return d;
        }
    }

    static final class InMemoryArtifactDao implements ArtifactDao {
        final Map<Long, ArtifactDO> rows = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong(30000);

        ArtifactDO artifact(long dispatchId, String name, String ossRef) {
            ArtifactDO a = new ArtifactDO();
            a.setId(ids.incrementAndGet());
            a.setTenantId(1L);
            a.setSourceType(ExecutionSourceType.WORKITEM.name());
            a.setWorkitemId(10L);
            a.setDispatchId(dispatchId);
            a.setName(name);
            a.setType("EVIDENCE");
            a.setOssRef(ossRef);
            a.setSize(128L);
            rows.put(a.getId(), a);
            return a;
        }

        @Override public void insert(ArtifactDO artifact) {
            if (artifact.getId() == null) {
                artifact.setId(ids.incrementAndGet());
            }
            rows.put(artifact.getId(), artifact);
        }

        @Override public ArtifactDO findById(Long id) {
            return id == null ? null : rows.get(id);
        }

        @Override public ArtifactDO findWorkitemByTenantAndId(Long tenantId, Long id) {
            ArtifactDO a = id == null ? null : rows.get(id);
            return a != null && a.getTenantId().equals(tenantId)
                    && ExecutionSourceType.WORKITEM.name().equals(a.getSourceType()) ? a : null;
        }

        @Override public ArtifactDO findBySourceAndId(Long tenantId, String sourceType, Long sourceId, Long id) {
            return findById(id);
        }

        @Override public List<ArtifactDO> listBySource(Long tenantId, String sourceType, Long sourceId, String type) {
            return new ArrayList<>(rows.values());
        }

        @Override public List<ArtifactDO> listByWorkitem(Long tenantId, Long workitemId) {
            return new ArrayList<>(rows.values());
        }

        @Override public List<ArtifactDO> listByWorkitemAndType(Long tenantId, Long workitemId, String type) {
            return new ArrayList<>(rows.values());
        }

        @Override public List<ArtifactDO> listByDispatch(Long tenantId, Long dispatchId) {
            List<ArtifactDO> out = new ArrayList<>();
            rows.values().stream().filter(a -> dispatchId.equals(a.getDispatchId())).forEach(out::add);
            return out;
        }

        @Override public int deleteById(Long tenantId, Long id) {
            return rows.remove(id) != null ? 1 : 0;
        }

        @Override public int deleteBySourceAndId(Long tenantId, String sourceType, Long sourceId, Long id) {
            return rows.remove(id) != null ? 1 : 0;
        }

        @Override public List<ArtifactDO> listUsageArtifacts(Long tenantId, String usageName, int offset, int limit) {
            return List.of();
        }

        @Override public int markExternalExposed(Long tenantId, Long workitemId, Long id, String externalShareRef) {
            return 1;
        }

        @Override public List<ArtifactDO> listExternalExposed(Long tenantId, Long workitemId) {
            return List.of();
        }

        @Override public ArtifactDO findExternalExposed(Long tenantId, Long workitemId, Long id) {
            return null;
        }
    }
}
