package com.aliyun.autowonder.workitem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Startup self-heal for the V040 workitem recycle-bin schema.
 *
 * <p>V040 (docs/migration/V076__workitem_recycle_bin_deleted_at.sql) is executed by
 * operators, so a database upgraded by hand can still lack {@code workitem.deleted_at}
 * and {@code idx_recycled} when the recycle-bin code is deployed. softDelete,
 * listRecycled and restore all reference the column, and the missing column turns every
 * delete into an "unknown column" SQL error surfaced as a system internal error. This
 * runner is the release-time migration check: it probes information_schema and adds
 * exactly the V040 objects when they are missing, so the deployment heals itself.
 *
 * <p>Check-then-add is not atomic across concurrently booting nodes: a node that loses
 * the race receives MySQL error 1060 (duplicate column) or 1061 (duplicate key name),
 * which is the wanted end state and therefore tolerated. A column that already exists
 * with a different shape is never rewritten — that stays an operator decision. Any
 * failure only logs a warning, mirroring {@code SystemAdminBootstrap}: boot must not
 * depend on this repair, and the next restart retries it.
 */
@Component
public class WorkitemRecycleBinSchemaBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkitemRecycleBinSchemaBootstrap.class);

    static final String ADD_DELETED_AT = "ALTER TABLE `workitem` "
            + "ADD COLUMN `deleted_at` DATETIME(3) DEFAULT NULL "
            + "COMMENT '软删除时间（回收站排序/筛选；NULL 表示未删除）' AFTER `is_deleted`";
    static final String ADD_RECYCLED_INDEX = "ALTER TABLE `workitem` "
            + "ADD KEY `idx_recycled` (`tenant_id`, `is_deleted`, `deleted_at`)";

    private static final int PROBE_TIMEOUT_SECONDS = 5;
    private static final int DDL_TIMEOUT_SECONDS = 300;
    private static final int ER_DUP_FIELDNAME = 1060;
    private static final int ER_DUP_KEYNAME = 1061;

    private final DataSource dataSource;

    public WorkitemRecycleBinSchemaBootstrap(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            repair(connection);
        } catch (Exception exception) {
            log.warn("workitem recycle-bin schema self-heal skipped; run "
                    + "docs/migration/V076__workitem_recycle_bin_deleted_at.sql manually", exception);
        }
    }

    void repair(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
            log.info("workitem recycle-bin schema self-heal targets MySQL only, skipped for {}", product);
            return;
        }
        if (!workitemTableExists(connection)) {
            log.warn("table workitem does not exist; recycle-bin schema self-heal skipped, "
                    + "apply docs/autowonder-schema.sql first");
            return;
        }
        ensureDeletedAtColumn(connection);
        ensureRecycledIndex(connection);
    }

    private boolean workitemTableExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workitem'")) {
            statement.setQueryTimeout(PROBE_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) > 0;
            }
        }
    }

    void ensureDeletedAtColumn(Connection connection) throws SQLException {
        String columnType = deletedAtColumnType(connection);
        if (columnType == null) {
            applyToleratingDuplicate(connection, ADD_DELETED_AT, ER_DUP_FIELDNAME, "workitem.deleted_at");
            log.info("Added missing column workitem.deleted_at (V040 recycle-bin self-heal)");
            return;
        }
        if (!"datetime(3)".equals(columnType.toLowerCase(Locale.ROOT))) {
            log.warn("workitem.deleted_at exists as {} which differs from the V040 contract datetime(3); "
                    + "left untouched, run docs/migration/V076__workitem_recycle_bin_deleted_at.sql manually",
                    columnType);
        }
    }

    void ensureRecycledIndex(Connection connection) throws SQLException {
        if (!recycledIndexExists(connection)) {
            applyToleratingDuplicate(connection, ADD_RECYCLED_INDEX, ER_DUP_KEYNAME, "workitem.idx_recycled");
            log.info("Added missing index workitem.idx_recycled (V040 recycle-bin self-heal)");
        }
    }

    private String deletedAtColumnType(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workitem' "
                        + "AND COLUMN_NAME = 'deleted_at'")) {
            statement.setQueryTimeout(PROBE_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private boolean recycledIndexExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workitem' "
                        + "AND INDEX_NAME = 'idx_recycled'")) {
            statement.setQueryTimeout(PROBE_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) > 0;
            }
        }
    }

    void applyToleratingDuplicate(Connection connection, String ddl, int duplicateErrorCode,
                                  String objectName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(DDL_TIMEOUT_SECONDS);
            statement.execute(ddl);
        } catch (SQLException exception) {
            if (exception.getErrorCode() == duplicateErrorCode) {
                // Another booting node or a concurrent operator migration added the object
                // first: the database already holds the state this repair wanted.
                log.info("{} was added concurrently by another process; nothing to do", objectName);
                return;
            }
            throw exception;
        }
    }
}
