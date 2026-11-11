package com.aliyun.autowonder.insights.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.aliyun.autowonder.insights.delivery.DeliveryAnalyticsData.*;

/** Bounded metadata reads: at most 200k rows/90s per build; never truncate a successful report. */
@Repository
public class DeliveryAnalyticsRepository {
    static final int MAX_ROWS=200_000, BATCH=100;
    private final DataSource source;
    public DeliveryAnalyticsRepository(DataSource source){this.source=source;}
    private static final String FORMAL="""
        d.source_type='WORKITEM'
        AND COALESCE(d.resume_mode,'') NOT IN ('COMMENT_INTERACTION','SIDE_INTERACTION','CANONICAL_INTERACTION')
        AND NOT EXISTS (SELECT 1 FROM agent ag WHERE ag.id=d.agent_id AND ag.tenant_id=d.tenant_id AND ag.kind='PLATFORM')
        """;
    private static final String EVENT_SELECT="""
        SELECT /*+ MAX_EXECUTION_TIME(10000) */ e.id,e.workitem_id,e.event_type,e.actor_type,e.gmt_create,
          JSON_UNQUOTE(JSON_EXTRACT(e.detail_json,'$.source')) source,
          CASE WHEN e.event_type='ASSIGN' THEN JSON_UNQUOTE(JSON_EXTRACT(e.detail_json,'$.fromType'))
            ELSE (SELECT n.category FROM status_node n WHERE n.tenant_id=e.tenant_id AND n.template_id=w.template_id
                 AND (n.code=e.from_val OR n.name=e.from_val) ORDER BY (n.code=e.from_val) DESC LIMIT 1) END from_category,
          CASE WHEN e.event_type='ASSIGN' THEN JSON_UNQUOTE(JSON_EXTRACT(e.detail_json,'$.toType'))
            ELSE (SELECT n.category FROM status_node n WHERE n.tenant_id=e.tenant_id AND n.template_id=w.template_id
                 AND (n.code=e.to_val OR n.name=e.to_val) ORDER BY (n.code=e.to_val) DESC LIMIT 1) END to_category
        FROM workitem_event e JOIN workitem w ON w.id=e.workitem_id AND w.tenant_id=e.tenant_id
        WHERE e.tenant_id=?
        """;
    private static final RowMapper<Event> EVENT=(r,i)->new Event(r.getLong("id"),r.getLong("workitem_id"),r.getString("event_type"),
        r.getString("actor_type"),r.getString("from_category"),r.getString("to_category"),r.getString("source"),date(r,"gmt_create"));
    public DeliveryAnalyticsData load(long tenant,LocalDateTime now) {
        var read=new Read(source);var start=now.minusDays(89).toLocalDate().atStartOfDay();
        var recent=read.query(EVENT_SELECT+"""
            AND e.event_type='STATUS_CHANGE' AND e.gmt_create>=? AND e.gmt_create<?
            AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(e.detail_json,'$.source')),'')<>'MIGRATION'
            """,EVENT,tenant,start,now);
        var ids=recent.stream().filter(e->"DONE".equals(e.to())&&!"DONE".equals(e.from())).map(Event::workitemId).distinct().toList();
        List<Event> history=new ArrayList<>(recent.stream().filter(e->e.to()==null).toList());Map<Long,Dispatch> dispatches=new HashMap<>();
        for(var batch:batches(ids)) {
            var args=args(tenant,batch,now);
            history.addAll(read.query(EVENT_SELECT+" AND e.workitem_id IN ("+marks(batch.size())+") AND e.gmt_create<? AND e.event_type IN ('ASSIGN','STATUS_CHANGE')",EVENT,args));
            for(var d:read.query("SELECT /*+ MAX_EXECUTION_TIME(10000) */ d.id,d.workitem_id,d.gmt_create FROM dispatch d WHERE d.tenant_id=? AND "+FORMAL+
                    " AND d.workitem_id IN ("+marks(batch.size())+") AND d.gmt_create<?",
                    (r,i)->new Dispatch(r.getLong("id"),r.getLong("workitem_id"),date(r,"gmt_create")),args))dispatches.put(d.id(),d);
        }
        var entries=read.query("""
            SELECT /*+ MAX_EXECUTION_TIME(10000) */ e.workitem_id,MIN(e.gmt_create) entered
            FROM workitem_event e WHERE e.tenant_id=? AND e.event_type IN ('CREATE','AONE_IMPORT','EXTERNAL_IMPORT')
            AND e.gmt_create>=? AND e.gmt_create<?
            AND NOT EXISTS (SELECT 1 FROM workitem_event p WHERE p.tenant_id=e.tenant_id AND p.workitem_id=e.workitem_id
                AND p.event_type IN ('CREATE','AONE_IMPORT','EXTERNAL_IMPORT') AND p.gmt_create<e.gmt_create)
            GROUP BY e.workitem_id
            """,(r,i)->new Entry(r.getLong("workitem_id"),date(r,"entered")),tenant,start,now);
        // Modified window includes executions begun before the display window.
        var active=read.query("SELECT /*+ MAX_EXECUTION_TIME(10000) */ d.id,d.workitem_id,d.gmt_create FROM dispatch d WHERE d.tenant_id=? AND "+FORMAL+
            " AND d.gmt_modified>=? AND d.gmt_create<?",
            (r,i)->new Dispatch(r.getLong("id"),r.getLong("workitem_id"),date(r,"gmt_create")),tenant,start,now);
        active.forEach(d->dispatches.put(d.id(),d));
        List<Edge> edges=new ArrayList<>();List<Wait> waits=new ArrayList<>();
        // All histories for each selected dispatch ensure an opening edge before the window is retained.
        for(var batch:batches(active.stream().map(Dispatch::id).toList())){
            var args=args(tenant,batch,now);
            edges.addAll(read.query("""
                SELECT /*+ MAX_EXECUTION_TIME(10000) */ id,dispatch_id,workitem_id,event_type,COALESCE(event_time,gmt_create) at_time,JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.sessionId')) session_id
                FROM dispatch_runtime_event FORCE INDEX (idx_dispatch_event_type) WHERE tenant_id=?
                AND dispatch_id IN ("""+marks(batch.size())+"""
                ) AND gmt_create<? AND event_type IN ('agent.started','session.started','session.resumed','session.forked',
                'session.completed','session.failed','session.interrupted','session.cancelled',
                'dispatch.completed','dispatch.failed','dispatch.cancelled','turn.started')
                """,(r,i)->new Edge(r.getLong("id"),r.getLong("dispatch_id"),r.getLong("workitem_id"),r.getString("event_type"),date(r,"at_time"),r.getString("session_id")),args));
            waits.addAll(read.query("SELECT /*+ MAX_EXECUTION_TIME(10000) */ dispatch_id,gmt_create,gmt_modified,status,expires_at FROM dispatch_request WHERE tenant_id=?"+
                " AND dispatch_id IN ("+marks(batch.size())+") AND gmt_create<? AND kind IN ('INPUT','APPROVAL')",
                waitMapper(now),args));
        }
        var skills=read.query("SELECT /*+ MAX_EXECUTION_TIME(10000) */ type,is_deleted,version,gmt_create,gmt_modified FROM skill WHERE tenant_id=? AND gmt_create<?",
            (r,i)->new Skill(r.getString("type"),r.getBoolean("is_deleted"),r.getInt("version"),date(r,"gmt_create"),date(r,"gmt_modified")),tenant,now);
        return new DeliveryAnalyticsData(history,List.copyOf(dispatches.values()),edges,waits,entries,skills);
    }
    static RowMapper<Wait> waitMapper(LocalDateTime now) {
        return (r,i)->{
            var modified=date(r,"gmt_modified");var expires=date(r,"expires_at");var status=r.getString("status");
            var end="PENDING".equals(status)?now:modified;
            if("EXPIRED".equals(status)&&expires!=null&&expires.isBefore(end))end=expires;
            return new Wait(r.getLong("dispatch_id"),date(r,"gmt_create"),end);
        };
    }
    static LocalDateTime date(ResultSet r,String col)throws SQLException {var t=r.getTimestamp(col);return t==null?null:t.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime();}
    static String marks(int n){return String.join(",",Collections.nCopies(n,"?"));}
    static Object[] args(long tenant,List<Long> ids,LocalDateTime now){var p=new ArrayList<Object>();p.add(tenant);p.addAll(ids);p.add(now);return p.toArray();}
    static List<List<Long>> batches(List<Long> ids){List<List<Long>> b=new ArrayList<>();for(int i=0;i<ids.size();i+=BATCH)b.add(ids.subList(i,Math.min(ids.size(),i+BATCH)));return b;}
    static class Read {
        final JdbcTemplate jdbc;final long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();int remaining=MAX_ROWS;
        Read(DataSource source){jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(10);jdbc.setFetchSize(500);}
        <T> List<T> query(String sql,RowMapper<T> mapper,Object... args){
            if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())throw new IllegalStateException("BUILD_TIMEOUT");
            jdbc.setMaxRows(remaining+1);
            // Match existing Date-based DAOs regardless of the application JVM timezone.
            Object[] bound=Arrays.stream(args).map(value->value instanceof LocalDateTime time
                ?Timestamp.from(time.atZone(ZoneId.of("Asia/Shanghai")).toInstant()):value).toArray();
            var rows=jdbc.query(sql,mapper,bound);
            remaining-=rows.size();
            if(remaining<0)throw new IllegalStateException("ROW_LIMIT");
            return rows;
        }
    }
}
