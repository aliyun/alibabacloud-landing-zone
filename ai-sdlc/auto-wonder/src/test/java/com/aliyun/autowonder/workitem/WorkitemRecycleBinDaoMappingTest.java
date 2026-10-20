package com.aliyun.autowonder.workitem;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回收站 SQL 约束：软删除记录删除时间、恢复走乐观锁且仅作用于已删除行。 */
class WorkitemRecycleBinDaoMappingTest {

    private String xml() throws Exception {
        return new String(
                getClass().getResourceAsStream("/mapping/WorkitemDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    void softDeleteRecordsDeletionTime() throws Exception {
        String xml = xml();
        int start = xml.indexOf("<update id=\"softDelete\">");
        int end = xml.indexOf("</update>", start);
        String block = xml.substring(start, end);
        assertTrue(block.contains("is_deleted = 1"), "softDelete should keep the soft-delete semantics");
        assertTrue(block.contains("deleted_at = NOW(3)"),
                "softDelete should record deleted_at so the recycle bin can sort by deletion time");
        assertTrue(block.contains("AND is_deleted = 0"), "softDelete should only delete visible workitems");
    }

    @Test
    void recycledListFiltersDeletedRowsAndOrdersByDeletedAtDesc() throws Exception {
        String xml = xml();
        assertTrue(xml.contains("<sql id=\"recycledFilter\">"),
                "recycle bin queries should share a dedicated filter fragment");
        int filterStart = xml.indexOf("<sql id=\"recycledFilter\">");
        int filterEnd = xml.indexOf("</sql>", filterStart);
        String filter = xml.substring(filterStart, filterEnd);
        assertTrue(filter.contains("w.is_deleted = 1"), "recycle bin should only list soft-deleted workitems");
        assertTrue(filter.contains("w.tenant_id = #{tenantId}"), "recycle bin should stay tenant-scoped");

        int listStart = xml.indexOf("<select id=\"listRecycled\"");
        int listEnd = xml.indexOf("</select>", listStart);
        String list = xml.substring(listStart, listEnd);
        assertTrue(list.contains("w.deleted_at DESC"),
                "recycle bin list should sort by deletion time, newest first");
        assertTrue(list.contains("LIMIT #{offset}, #{limit}"),
                "recycle bin list should paginate server-side like the regular list");
    }

    @Test
    void restoreUsesOptimisticLockAndOnlyTargetsDeletedRows() throws Exception {
        String xml = xml();
        int start = xml.indexOf("<update id=\"restore\">");
        int end = xml.indexOf("</update>", start);
        String block = xml.substring(start, end);
        assertTrue(block.contains("is_deleted = 0") && block.contains("deleted_at = NULL"),
                "restore should clear the deletion markers");
        assertTrue(block.contains("version = #{version}"),
                "restore should use the optimistic-lock version to prevent concurrent restores");
        assertTrue(block.contains("AND is_deleted = 1"),
                "restore should only affect soft-deleted workitems so repeat restores are rejected");
    }

    @Test
    void findByIdIncludingDeletedExistsForRestoreLookup() throws Exception {
        String xml = xml();
        int start = xml.indexOf("<select id=\"findByIdIncludingDeleted\"");
        assertTrue(start > 0, "restore needs a lookup that can see soft-deleted workitems");
        int end = xml.indexOf("</select>", start);
        String block = xml.substring(start, end);
        assertTrue(!block.contains("is_deleted"),
                "findByIdIncludingDeleted must not filter is_deleted so restore can inspect deleted rows");
    }
}
