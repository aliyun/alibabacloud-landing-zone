package com.aliyun.autowonder.workspace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the SQL semantics of AccessRequestDao.xml that the service layer depends on but that
 * a statement-id contract test cannot see: the compare-and-set review predicate, and the
 * deliberate absence of the generated {@code pending_marker} column.
 */
class AccessRequestDaoSqlTest {

    @Test
    void reviewIsACompareAndSetOnPendingSoConcurrentApprovalsCannotBothWin() throws Exception {
        String updateStatus = statement(accessRequestMapperXml(), "updateStatus", "update");

        // The service layer detects a lost review race by the affected-row count being 0.
        // Drop this predicate and two admins approving concurrently would both "succeed".
        assertTrue(updateStatus.contains("WHERE id = #{id} AND status = 'PENDING'"),
                "updateStatus must compare-and-set on status = 'PENDING' so a lost race "
                        + "reports 0 affected rows, got: " + updateStatus);
    }

    @Test
    void cancelIsAPhysicalDeleteGuardedByPendingSoReviewedRecordsSurvive() throws Exception {
        String deletePendingById = statement(accessRequestMapperXml(), "deletePendingById", "delete");

        // Cancellation is a product-mandated physical delete, but only while PENDING: dropping
        // the guard would erase APPROVED/REJECTED history, and losing the race against a
        // concurrent review must report 0 affected rows so the service answers NOT_FOUND.
        assertTrue(deletePendingById.contains("DELETE FROM workspace_access_request"),
                "deletePendingById must physically delete the row, got: " + deletePendingById);
        assertTrue(deletePendingById.contains("WHERE id = #{id} AND status = 'PENDING'"),
                "deletePendingById must guard on status = 'PENDING' so reviewed requests "
                        + "survive and a lost race reports 0 affected rows, got: " + deletePendingById);
    }

    @Test
    void pendingMarkerIsNeitherSelectedNorWrittenAnywhereInTheMapper() throws Exception {
        String xml = accessRequestMapperXml();

        // pending_marker is a STORED GENERATED column: writing it is a MySQL error, and
        // selecting it is pointless because AccessRequestDO deliberately has no such field.
        assertFalse(xml.contains("pending_marker"),
                "AccessRequestDao.xml must never reference pending_marker (STORED GENERATED "
                        + "column: writing it errors, selecting it maps to nothing), got: " + xml);
    }

    @Test
    void selectedColumnsMatchTheMappedDoFieldsExactly() throws Exception {
        String cols = statement(accessRequestMapperXml(), "cols", "sql")
                .replace("<sql id=\"cols\">", "")
                .trim();

        // Compared by equality, not containment, so an appended column (such as the
        // generated pending_marker) cannot slip past this assertion.
        assertEquals("id, tenant_id, requester_id, requested_level, status, "
                        + "reviewer_id, reject_reason, gmt_create, gmt_modified",
                cols,
                "cols must list exactly the columns mapped onto AccessRequestDO");
    }

    private String accessRequestMapperXml() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapping/AccessRequestDao.xml")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
        }
    }

    private static String statement(String xml, String id, String element) {
        String marker = "id=\"" + id + "\"";
        int idStart = xml.indexOf(marker);
        assertTrue(idStart >= 0, "missing mapper statement " + id);
        int statementStart = xml.lastIndexOf("<" + element, idStart);
        int statementEnd = xml.indexOf("</" + element + ">", idStart);
        assertTrue(statementStart >= 0, "missing opening " + element + " for " + id);
        assertTrue(statementEnd >= 0, "missing closing " + element + " for " + id);
        return xml.substring(statementStart, statementEnd);
    }
}
