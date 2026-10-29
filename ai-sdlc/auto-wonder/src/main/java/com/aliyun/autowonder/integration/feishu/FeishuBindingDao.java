package com.aliyun.autowonder.integration.feishu;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.Types;
import java.util.List;

@Repository
public class FeishuBindingDao {
    private final JdbcTemplate jdbc;
    private final BeanPropertyRowMapper<FeishuBinding> mapper = new BeanPropertyRowMapper<>(FeishuBinding.class);

    public FeishuBindingDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<FeishuBinding> list(Long tenantId) {
        return jdbc.query("SELECT * FROM feishu_robot_binding WHERE tenant_id=? ORDER BY id DESC", mapper, tenantId);
    }

    public FeishuBinding find(Long tenantId, Long id) {
        return jdbc.query("SELECT * FROM feishu_robot_binding WHERE tenant_id=? AND id=?", mapper, tenantId, id)
                .stream().findFirst().orElse(null);
    }

    /** Row-locking read used by the delivery fence: blocks binding edits until the send ends. */
    public FeishuBinding findForUpdate(Long tenantId, Long id) {
        return jdbc.query("SELECT * FROM feishu_robot_binding WHERE tenant_id=? AND id=? FOR UPDATE", mapper, tenantId, id)
                .stream().findFirst().orElse(null);
    }

    // Only authenticated callback routing / background delivery may use the global lookup.
    public FeishuBinding findGlobal(Long id) {
        return jdbc.query("SELECT * FROM feishu_robot_binding WHERE id=?", mapper, id).stream().findFirst().orElse(null);
    }

    // The historical bindingId callback must never authenticate a shared binding.
    public FeishuBinding findLegacyGlobal(Long id) {
        return jdbc.query("SELECT * FROM feishu_robot_binding WHERE id=? AND binding_mode='LEGACY'", mapper, id)
                .stream().findFirst().orElse(null);
    }

    public FeishuBinding findLegacyByAppIdGlobal(String appId) {
        return jdbc.query("SELECT * FROM feishu_robot_binding WHERE app_id=? AND binding_mode='LEGACY' LIMIT 1", mapper, appId)
                .stream().findFirst().orElse(null);
    }

    public FeishuBinding findSharedByConfigAndGroup(Long platformConfigId, String groupId) {
        return jdbc.query("SELECT * FROM feishu_robot_binding WHERE platform_config_id=? AND group_id=? AND binding_mode='SHARED' LIMIT 1",
                mapper, platformConfigId, groupId).stream().findFirst().orElse(null);
    }

    public void insert(FeishuBinding row) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO feishu_robot_binding (tenant_id,binding_mode,platform_config_id,group_id,app_id,credential_ref,agent_id,route_version,status,creator_id,modifier_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, row.getTenantId());
            ps.setString(2, row.getBindingMode() == null ? "LEGACY" : row.getBindingMode());
            if (row.getPlatformConfigId() == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, row.getPlatformConfigId());
            }
            ps.setString(4, row.getGroupId());
            ps.setString(5, row.getAppId());
            ps.setString(6, row.getCredentialRef());
            ps.setLong(7, row.getAgentId());
            ps.setLong(8, row.getRouteVersion() == null ? 0L : row.getRouteVersion());
            ps.setString(9, row.getStatus());
            ps.setLong(10, row.getCreatorId());
            ps.setLong(11, row.getModifierId());
            return ps;
        }, keys);
        row.setId(keys.getKey().longValue());
        row.setVersion(0);
        if (row.getRouteVersion() == null) row.setRouteVersion(0L);
    }

    public int update(FeishuBinding row) {
        return jdbc.update("UPDATE feishu_robot_binding SET credential_ref=?,agent_id=?,status=?,route_version=?,modifier_id=?,version=version+1 WHERE tenant_id=? AND id=? AND version=? AND binding_mode='LEGACY'",
                row.getCredentialRef(), row.getAgentId(), row.getStatus(), row.getRouteVersion(),
                row.getModifierId(), row.getTenantId(), row.getId(), row.getVersion());
    }

    public int updateShared(FeishuBinding row) {
        return jdbc.update("UPDATE feishu_robot_binding SET group_id=?,agent_id=?,status=?,route_version=?,modifier_id=?,version=version+1 WHERE tenant_id=? AND id=? AND version=? AND binding_mode='SHARED'",
                row.getGroupId(), row.getAgentId(), row.getStatus(), row.getRouteVersion(),
                row.getModifierId(), row.getTenantId(), row.getId(), row.getVersion());
    }

    public void delete(Long tenantId, Long id) {
        jdbc.update("DELETE FROM feishu_robot_binding WHERE tenant_id=? AND id=?", tenantId, id);
    }

    public void health(FeishuBinding row, String error) {
        if (error == null) {
            jdbc.update("UPDATE feishu_robot_binding SET last_success_at=CURRENT_TIMESTAMP,last_error=NULL WHERE tenant_id=? AND id=?", row.getTenantId(), row.getId());
        } else {
            jdbc.update("UPDATE feishu_robot_binding SET last_error=? WHERE tenant_id=? AND id=?", error, row.getTenantId(), row.getId());
        }
    }
}
