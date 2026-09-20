package com.aliyun.autowonder.integration.feishu;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
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

    // Only authenticated callback routing / background delivery may use the global lookup.
    public FeishuBinding findGlobal(Long id) {
        return jdbc.query("SELECT * FROM feishu_robot_binding WHERE id=?", mapper, id).stream().findFirst().orElse(null);
    }

    public void insert(FeishuBinding row) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO feishu_robot_binding (tenant_id,app_id,credential_ref,agent_id,status,creator_id,modifier_id) VALUES (?,?,?,?,?,?,?)", new String[]{"id"});
            ps.setLong(1, row.getTenantId()); ps.setString(2, row.getAppId());
            ps.setString(3, row.getCredentialRef()); ps.setLong(4, row.getAgentId());
            ps.setString(5, row.getStatus()); ps.setLong(6, row.getCreatorId()); ps.setLong(7, row.getModifierId());
            return ps;
        }, keys);
        row.setId(keys.getKey().longValue());
        row.setVersion(0);
    }

    public int update(FeishuBinding row) {
        return jdbc.update("UPDATE feishu_robot_binding SET credential_ref=?,agent_id=?,status=?,modifier_id=?,version=version+1 WHERE tenant_id=? AND id=? AND version=?",
                row.getCredentialRef(), row.getAgentId(), row.getStatus(), row.getModifierId(), row.getTenantId(), row.getId(), row.getVersion());
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
