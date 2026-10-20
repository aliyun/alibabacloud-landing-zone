package com.aliyun.autowonder.squad;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复制请求的幂等回放与失败补偿语义全部落在 SQL 上：
 * 唯一键槽位由 insert 占住，完成/失败只允许从 IN_PROGRESS 推进一次。
 * 一旦守卫条件漂移，重复请求会改写已完成结果或吞掉补偿信息，服务层测试察觉不到。
 */
class SquadCopyOperationDaoContractTest {

    @Test
    void insertOccupiesTheIdempotencySlotWithGeneratedId() throws Exception {
        String insert = statement("insert", "insert");

        for (String column : new String[]{"source_workspace_id", "source_squad_id", "user_id", "request_id",
                "target_workspace_id", "request_fingerprint", "source_snapshot_ref", "status"}) {
            assertTrue(insert.contains(column), "insert 必须写入 " + column + "，实际: " + insert);
        }
        assertTrue(insert.contains("useGeneratedKeys=\"true\""), "回填结果依赖自增主键，实际: " + insert);
        assertTrue(insert.contains("keyProperty=\"id\""), "回填结果依赖自增主键，实际: " + insert);
    }

    @Test
    void findByUniqueKeyUsesAllFourRequestColumns() throws Exception {
        String find = statement("find", "select");

        assertTrue(find.contains("source_workspace_id = #{sourceWorkspaceId}"), "实际: " + find);
        assertTrue(find.contains("user_id = #{userId}"), "实际: " + find);
        assertTrue(find.contains("request_id = #{requestId}"), "实际: " + find);
        assertTrue(find.contains("target_workspace_id = #{targetWorkspaceId}"), "实际: " + find);
        assertTrue(find.contains("LIMIT 1"), "单条回放查询不应把整表结果带回，实际: " + find);
    }

    @Test
    void listByRequestCoversTheWholeRequestInStableTargetOrder() throws Exception {
        String list = statement("listByRequest", "select");

        assertTrue(list.contains("source_workspace_id = #{sourceWorkspaceId}"), "实际: " + list);
        assertTrue(list.contains("user_id = #{userId}"), "实际: " + list);
        assertTrue(list.contains("request_id = #{requestId}"), "实际: " + list);
        assertFalse(list.contains("target_workspace_id ="),
                "回放要看到整请求下所有目标空间的结果，不能按单目标过滤，实际: " + list);
        assertTrue(list.contains("ORDER BY target_workspace_id ASC"),
                "回放结果顺序必须稳定，与请求内目标顺序无关地可重复，实际: " + list);
    }

    @Test
    void markSuccessOnlyAdvancesInProgressRowsAndClearsFailureTraces() throws Exception {
        String update = statement("markSuccess", "update");

        assertTrue(update.contains("status = 'SUCCESS'"), "实际: " + update);
        assertTrue(update.contains("target_squad_id = #{targetSquadId}"), "实际: " + update);
        assertTrue(update.contains("error_code = NULL"), "成功后不得残留上一次失败的错误码，实际: " + update);
        assertTrue(update.contains("error_message = NULL"), "实际: " + update);
        assertTrue(update.contains("resource_cleanup_json = NULL"),
                "成功后不得残留补偿记录，实际: " + update);
        assertTrue(update.contains("gmt_modified = NOW(3)"), "实际: " + update);
        assertTrue(update.contains("WHERE id = #{id} AND status = 'IN_PROGRESS'"),
                "守卫必须限定处理中状态，已完成结果不被重复请求改写，实际: " + update);
    }

    @Test
    void markFailedOnlyAdvancesInProgressRowsAndKeepsCleanupPayload() throws Exception {
        String update = statement("markFailed", "update");

        assertTrue(update.contains("status = 'FAILED'"), "实际: " + update);
        assertTrue(update.contains("error_code = #{errorCode}"), "实际: " + update);
        assertTrue(update.contains("error_message = #{errorMessage}"), "实际: " + update);
        assertTrue(update.contains("resource_cleanup_json = #{resourceCleanupJson}"),
                "失败记录的补偿状态必须落库，实际: " + update);
        assertTrue(update.contains("gmt_modified = NOW(3)"), "实际: " + update);
        assertTrue(update.contains("WHERE id = #{id} AND status = 'IN_PROGRESS'"),
                "守卫必须限定处理中状态，已完成结果不被重复请求改写，实际: " + update);
    }

    private static String statement(String id, String tag) throws Exception {
        String xml = new String(
                SquadCopyOperationDaoContractTest.class.getResourceAsStream("/mapping/SquadCopyOperationDao.xml")
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"" + id + "\"");
        assertTrue(start >= 0, "SquadCopyOperationDao.xml 缺少 " + tag + " id=" + id);
        int end = xml.indexOf("</" + tag + ">", start);
        assertTrue(end > start, "unterminated " + tag + " " + id);
        return xml.substring(start, end);
    }
}
