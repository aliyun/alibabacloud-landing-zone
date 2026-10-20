package com.aliyun.autowonder.workitem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V040 启动自愈：缺失对象补齐、并发重复容忍、既有对象不动，且任何失败都不阻塞启动。 */
class WorkitemRecycleBinSchemaBootstrapTest {

    private Connection connection;
    private Statement ddl;
    private PreparedStatement tableProbe;
    private PreparedStatement columnProbe;
    private PreparedStatement indexProbe;
    private ResultSet tableResult;
    private ResultSet columnResult;
    private ResultSet indexResult;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        tableProbe = mock(PreparedStatement.class);
        columnProbe = mock(PreparedStatement.class);
        indexProbe = mock(PreparedStatement.class);
        tableResult = mock(ResultSet.class);
        columnResult = mock(ResultSet.class);
        indexResult = mock(ResultSet.class);
        ddl = mock(Statement.class);

        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(connection.prepareStatement(contains("information_schema.TABLES"))).thenReturn(tableProbe);
        when(connection.prepareStatement(contains("information_schema.COLUMNS"))).thenReturn(columnProbe);
        when(connection.prepareStatement(contains("information_schema.STATISTICS"))).thenReturn(indexProbe);
        when(tableProbe.executeQuery()).thenReturn(tableResult);
        when(columnProbe.executeQuery()).thenReturn(columnResult);
        when(indexProbe.executeQuery()).thenReturn(indexResult);
        when(connection.createStatement()).thenReturn(ddl);
        when(tableResult.next()).thenReturn(true);
        when(tableResult.getLong(1)).thenReturn(1L);
        when(indexResult.next()).thenReturn(true);
    }

    private void workitemTableExists(boolean exists) throws SQLException {
        when(tableResult.getLong(1)).thenReturn(exists ? 1L : 0L);
    }

    private void columnType(String type) throws SQLException {
        when(columnResult.next()).thenReturn(type != null);
        if (type != null) {
            when(columnResult.getString(1)).thenReturn(type);
        }
    }

    private void indexExists(boolean exists) throws SQLException {
        when(indexResult.getLong(1)).thenReturn(exists ? 1L : 0L);
    }

    @Test
    void runNeverThrowsWhenTheDataSourceIsUnavailable() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("database unreachable"));
        WorkitemRecycleBinSchemaBootstrap bootstrap = new WorkitemRecycleBinSchemaBootstrap(dataSource);

        assertDoesNotThrow(() -> bootstrap.run(null));
    }

    @Test
    void runSkipsNonMySQLDatabasesWithoutTouchingIt() {
        DataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:recycle_bin_bootstrap_skip;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        WorkitemRecycleBinSchemaBootstrap bootstrap = new WorkitemRecycleBinSchemaBootstrap(h2);

        assertDoesNotThrow(() -> bootstrap.run(null));
    }

    @Test
    void runSwallowsRepairFailuresSoBootIsNeverBlocked() throws Exception {
        when(tableProbe.executeQuery()).thenThrow(new SQLException("probe failed"));
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        WorkitemRecycleBinSchemaBootstrap bootstrap = new WorkitemRecycleBinSchemaBootstrap(dataSource);

        assertDoesNotThrow(() -> bootstrap.run(null));
    }

    @Test
    void repairSkipsNonMySQLProducts() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(connection.getMetaData()).thenReturn(metadata);

        new WorkitemRecycleBinSchemaBootstrap(mock(DataSource.class)).repair(connection);

        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void repairSkipsWhenWorkitemTableIsMissing() throws Exception {
        workitemTableExists(false);

        new WorkitemRecycleBinSchemaBootstrap(mock(DataSource.class)).repair(connection);

        verify(connection, never()).prepareStatement(contains("information_schema.COLUMNS"));
        verify(connection, never()).createStatement();
    }

    @Test
    void repairAddsMissingColumnAndIndex() throws Exception {
        columnType(null);
        indexExists(false);

        new WorkitemRecycleBinSchemaBootstrap(mock(DataSource.class)).repair(connection);

        verify(ddl).execute(WorkitemRecycleBinSchemaBootstrap.ADD_DELETED_AT);
        verify(ddl).execute(WorkitemRecycleBinSchemaBootstrap.ADD_RECYCLED_INDEX);
    }

    @Test
    void repairLeavesContractCompliantObjectsUntouched() throws Exception {
        columnType("datetime(3)");
        indexExists(true);

        new WorkitemRecycleBinSchemaBootstrap(mock(DataSource.class)).repair(connection);

        verify(connection, never()).createStatement();
    }

    @Test
    void repairKeepsMismatchedColumnForOperatorDecision() throws Exception {
        columnType("datetime");
        indexExists(true);

        new WorkitemRecycleBinSchemaBootstrap(mock(DataSource.class)).repair(connection);

        verify(connection, never()).createStatement();
    }

    @Test
    void repairToleratesColumnAddedConcurrently() throws Exception {
        columnType(null);
        indexExists(true);
        when(ddl.execute(WorkitemRecycleBinSchemaBootstrap.ADD_DELETED_AT))
                .thenThrow(new SQLException("duplicate column", "42S21", 1060));

        assertDoesNotThrow(() -> new WorkitemRecycleBinSchemaBootstrap(mock(DataSource.class)).repair(connection));

        verify(ddl).execute(WorkitemRecycleBinSchemaBootstrap.ADD_DELETED_AT);
    }

    @Test
    void repairToleratesIndexAddedConcurrently() throws Exception {
        columnType("datetime(3)");
        indexExists(false);
        when(ddl.execute(WorkitemRecycleBinSchemaBootstrap.ADD_RECYCLED_INDEX))
                .thenThrow(new SQLException("duplicate key name", "42000", 1061));

        assertDoesNotThrow(() -> new WorkitemRecycleBinSchemaBootstrap(mock(DataSource.class)).repair(connection));

        verify(ddl).execute(WorkitemRecycleBinSchemaBootstrap.ADD_RECYCLED_INDEX);
    }

    @Test
    void repairPropagatesUnexpectedSqlFailures() throws Exception {
        columnType(null);
        indexExists(true);
        when(ddl.execute(WorkitemRecycleBinSchemaBootstrap.ADD_DELETED_AT))
                .thenThrow(new SQLException("table gone", "42S02", 1146));
        WorkitemRecycleBinSchemaBootstrap bootstrap = new WorkitemRecycleBinSchemaBootstrap(mock(DataSource.class));

        assertThrows(SQLException.class, () -> bootstrap.repair(connection));
    }
}
