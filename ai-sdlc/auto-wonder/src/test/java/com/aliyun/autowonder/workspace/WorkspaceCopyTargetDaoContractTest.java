package com.aliyun.autowonder.workspace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复制目标候选的可见性完全由 copyTargetFilter 决定：
 * 列表与总数必须共用同一 fragment，条件漂移会让翻页出现空页；
 * 成员资格过滤只看 workspace_role，与迁移保留的 org_member.access_level 无关。
 */
class WorkspaceCopyTargetDaoContractTest {

    @Test
    void filterExcludesSourceDeletedAndDisabledWorkspaces() throws Exception {
        String filter = fragment("copyTargetFilter");

        assertTrue(filter.contains("o.is_deleted = 0"), "已删除空间不得进入候选，实际: " + filter);
        assertTrue(filter.contains("o.status = 0"), "停用空间不得进入候选，实际: " + filter);
        assertTrue(filter.contains("o.id != #{sourceWorkspaceId}"), "源空间不得作为自己的复制目标，实际: " + filter);
    }

    @Test
    void keywordMatchesNameOnly() throws Exception {
        String filter = fragment("copyTargetFilter");

        assertTrue(filter.contains("o.name LIKE CONCAT('%', #{keyword}, '%')"), "实际: " + filter);
        assertFalse(filter.contains("description"),
                "只按名称匹配：描述命中的空间不进入候选，实际: " + filter);
    }

    @Test
    void memberEligibilityReadsTheRoleTable() throws Exception {
        String filter = fragment("copyTargetFilter");

        assertTrue(filter.contains("test=\"!systemAdmin\""),
                "平台管理员不加成员过滤，实际: " + filter);
        assertTrue(filter.contains("FROM org_member m"), "实际: " + filter);
        assertTrue(filter.contains("JOIN workspace_role r ON r.id = m.role_id AND r.tenant_id = m.tenant_id"),
                "角色必须属于目标空间，实际: " + filter);
        assertTrue(filter.contains("m.tenant_id = o.id"), "成员归属必须指向候选空间，实际: " + filter);
        assertTrue(filter.contains("m.user_id = #{userId}"), "实际: " + filter);
        assertTrue(filter.contains("m.status = 0"), "非在职成员不得成为复制目标，实际: " + filter);
        assertTrue(filter.contains("m.is_deleted = 0"), "实际: " + filter);
        assertTrue(filter.contains("r.is_deleted = 0"), "实际: " + filter);
        assertTrue(filter.contains("r.access_level IN ('READ_WRITE', 'ADMIN')"),
                "只读成员不可写入复制结果，实际: " + filter);
    }

    @Test
    void listAndCountShareTheSameFilter() throws Exception {
        assertTrue(statement("listCopyTargets", "select").contains("<include refid=\"copyTargetFilter\"/>"),
                "列表必须复用共享条件");
        assertTrue(statement("countCopyTargets", "select").contains("<include refid=\"copyTargetFilter\"/>"),
                "总数必须复用共享条件，否则翻页出现空页");
    }

    @Test
    void listOrdersStablyAndPaginates() throws Exception {
        String list = statement("listCopyTargets", "select");

        assertTrue(list.contains("FROM `org` o"), "实际: " + list);
        assertTrue(list.contains("ORDER BY o.gmt_create DESC, o.id DESC"),
                "同刻创建的空间需要确定性次序，实际: " + list);
        assertTrue(list.contains("LIMIT #{limit} OFFSET #{offset}"), "实际: " + list);
    }

    @Test
    void countDoesNotPaginate() throws Exception {
        String count = statement("countCopyTargets", "select");

        assertTrue(count.contains("SELECT COUNT(*)"), "实际: " + count);
        assertTrue(count.contains("FROM `org` o"), "实际: " + count);
        assertFalse(count.contains("LIMIT"), "总数语句带 LIMIT 会让总数恒为页容量，实际: " + count);
    }

    private static String fragment(String id) throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<sql id=\"" + id + "\">");
        assertTrue(start >= 0, "WorkspaceDao.xml 缺少 sql fragment " + id);
        int end = xml.indexOf("</sql>", start);
        assertTrue(end > start, "unterminated sql fragment " + id);
        return xml.substring(start, end);
    }

    private static String statement(String id, String tag) throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("id=\"" + id + "\"");
        assertTrue(start >= 0, "WorkspaceDao.xml 缺少 " + tag + " id=" + id);
        int end = xml.indexOf("</" + tag + ">", start);
        assertTrue(end > start, "unterminated " + tag + " " + id);
        return xml.substring(start, end);
    }

    private static String mapperXml() throws Exception {
        return new String(
                WorkspaceCopyTargetDaoContractTest.class.getResourceAsStream("/mapping/WorkspaceDao.xml")
                        .readAllBytes(),
                StandardCharsets.UTF_8);
    }
}
