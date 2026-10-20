package com.aliyun.autowonder.workitem;

import org.junit.jupiter.api.Test;

class WorkitemCommentDaoMappingTest {

    @Test
    void listMyLastCommentTimesGroupsMyHumanCommentsPerWorkitem() throws Exception {
        for (String databaseId : java.util.List.of("autowonder-legacy", "autowonder-source-aware")) {
            boolean sourceAware = databaseId.equals("autowonder-source-aware");
            var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:h2:mem:comment-times-" + databaseId + ";MODE=MySQL", "sa", "");
            try (var connection = ds.getConnection(); var statement = connection.createStatement()) {
                statement.execute(sourceAware
                        ? "CREATE TABLE workitem_comment (id BIGINT PRIMARY KEY, tenant_id BIGINT, "
                        + "source_type VARCHAR(32) DEFAULT 'WORKITEM', workitem_id BIGINT, "
                        + "author_type VARCHAR(20), author_ref BIGINT, content_md VARCHAR(200), gmt_create TIMESTAMP)"
                        : "CREATE TABLE workitem_comment (id BIGINT PRIMARY KEY, tenant_id BIGINT, "
                        + "workitem_id BIGINT, author_type VARCHAR(20), author_ref BIGINT, "
                        + "content_md VARCHAR(200), gmt_create TIMESTAMP)");
                if (sourceAware) {
                    statement.execute("""
                            INSERT INTO workitem_comment
                            (id,tenant_id,source_type,workitem_id,author_type,author_ref,content_md,gmt_create) VALUES
                            (1,7,'WORKITEM',1,'HUMAN',42,'mine old','2026-02-01 10:00:00'),
                            (2,7,'WORKITEM',1,'HUMAN',42,'mine new','2026-02-03 10:00:00'),
                            (3,7,'WORKITEM',1,'HUMAN',99,'someone else later','2026-02-05 10:00:00'),
                            (4,7,'WORKITEM',2,'HUMAN',99,'not mine','2026-02-06 10:00:00'),
                            (5,7,'WORKITEM',2,'AGENT',42,'agent echo with my id','2026-02-07 10:00:00'),
                            (6,7,'WORKITEM',3,'HUMAN',42,'mine','2026-02-04 10:00:00'),
                            (7,7,'SCHEDULED_TASK_RUN',1,'HUMAN',42,'run comment','2026-02-08 10:00:00'),
                            (8,8,'WORKITEM',3,'HUMAN',42,'mine in another tenant','2026-02-09 10:00:00')
                            """);
                } else {
                    statement.execute("""
                            INSERT INTO workitem_comment (id,tenant_id,workitem_id,author_type,author_ref,gmt_create) VALUES
                            (1,7,1,'HUMAN',42,'2026-02-01 10:00:00'),(2,7,1,'HUMAN',42,'2026-02-03 10:00:00'),
                            (3,7,1,'HUMAN',99,'2026-02-05 10:00:00'),(4,7,2,'HUMAN',99,'2026-02-06 10:00:00'),
                            (5,7,2,'AGENT',42,'2026-02-07 10:00:00'),(6,7,3,'HUMAN',42,'2026-02-04 10:00:00'),
                            (8,8,3,'HUMAN',42,'2026-02-09 10:00:00')
                            """);
                }

                var configuration = new org.apache.ibatis.session.Configuration();
                configuration.setDatabaseId(databaseId);
                configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("test",
                        new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), ds));
                try (var input = getClass().getResourceAsStream("/mapping/WorkitemCommentDao.xml")) {
                    new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration,
                            "mapping/WorkitemCommentDao.xml", configuration.getSqlFragments()).parse();
                }
                try (var session = new org.apache.ibatis.session.SqlSessionFactoryBuilder()
                        .build(configuration).openSession()) {
                    java.util.List<WorkitemCommentDO> rows = session.selectList(
                            WorkitemCommentDao.class.getName() + ".listMyLastCommentTimes",
                            java.util.Map.of("tenantId", 7L, "workitemIds", java.util.List.of(1L, 2L, 3L),
                                    "authorRef", 42L));

                    org.junit.jupiter.api.Assertions.assertEquals(2, rows.size(),
                            databaseId + " should return one row per workitem I commented on, deduplicated");
                    var byWorkitem = rows.stream().collect(java.util.stream.Collectors.toMap(
                            WorkitemCommentDO::getWorkitemId, WorkitemCommentDO::getGmtCreate));
                    long expectedLatestOnFirst = java.sql.Timestamp.valueOf("2026-02-03 10:00:00").getTime();
                    org.junit.jupiter.api.Assertions.assertEquals(expectedLatestOnFirst,
                            byWorkitem.get(1L).getTime(),
                            databaseId + " should keep my latest comment time, not newer comments from others"
                                    + " or from the same runId shape used by scheduled-task-run comments");
                    org.junit.jupiter.api.Assertions.assertEquals(
                            java.sql.Timestamp.valueOf("2026-02-04 10:00:00").getTime(),
                            byWorkitem.get(3L).getTime(),
                            databaseId + " should keep my comment time on workitem 3");
                    org.junit.jupiter.api.Assertions.assertFalse(byWorkitem.containsKey(2L),
                            databaseId + " should ignore agent echoes and other people's comments");
                }
            }
        }
    }
}
