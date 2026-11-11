package com.aliyun.autowonder.insights.delivery;

import java.time.LocalDateTime;
import java.util.List;

/** Metadata only. Never retain comment bodies, runtime text, credentials or identities in responses. */
public record DeliveryAnalyticsData(List<Event> events, List<Dispatch> dispatches, List<Edge> edges,
        List<Wait> waits, List<Entry> entries, List<Skill> skills) {
    public record Event(long id,long workitemId,String type,String actor,String from,String to,
                        String source,LocalDateTime at) {}
    public record Dispatch(long id,long workitemId,LocalDateTime at) {}
    public record Edge(long id,long dispatchId,long workitemId,String type,LocalDateTime at,String session) {
        public Edge(long id,long dispatchId,long workitemId,String type,LocalDateTime at) {
            this(id,dispatchId,workitemId,type,at,null);
        }
    }
    public record Wait(long dispatchId,LocalDateTime start,LocalDateTime end) {}
    public record Entry(long workitemId,LocalDateTime at) {}
    public record Skill(String type,boolean deleted,int version,LocalDateTime created,LocalDateTime modified) {}
}
