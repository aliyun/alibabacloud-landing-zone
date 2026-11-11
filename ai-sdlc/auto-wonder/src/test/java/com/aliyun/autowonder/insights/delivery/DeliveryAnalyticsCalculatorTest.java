package com.aliyun.autowonder.insights.delivery;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aliyun.autowonder.insights.delivery.DeliveryAnalyticsData.*;

class DeliveryAnalyticsCalculatorTest {
    final LocalDateTime now=LocalDateTime.parse("2026-11-03T18:00:00");
    Event event(long id,String type,String actor,String from,String to,String when) {
        return new Event(id,1,type,actor,from,to,null,LocalDateTime.parse(when));
    }
    @Test void reopenedRoundsSubtractFinalHandoffAndAttributeFullDurationToStart() {
        var events=List.of(
            event(1,"ASSIGN","HUMAN","HUMAN","AGENT","2026-10-10T10:00:00"),
            event(2,"ASSIGN","AGENT","AGENT","HUMAN","2026-10-11T10:00:00"),
            event(3,"ASSIGN","HUMAN","HUMAN","AGENT","2026-10-12T10:00:00"),
            event(4,"ASSIGN","AGENT","AGENT","HUMAN","2026-10-13T10:00:00"),
            event(5,"STATUS_CHANGE","HUMAN","IN_PROGRESS","DONE","2026-10-20T10:00:00"),
            event(6,"STATUS_CHANGE","HUMAN","DONE","NEW","2026-10-21T10:00:00"),
            event(7,"ASSIGN","HUMAN","HUMAN","AGENT","2026-10-22T10:00:00"),
            event(8,"STATUS_CHANGE","HUMAN","IN_PROGRESS","DONE","2026-10-23T10:00:00"));
        var report=new DeliveryAnalyticsCalculator(new MainlandWorkCalendar()).calculate(
            new DeliveryAnalyticsData(events,List.of(
                new Dispatch(1,1,LocalDateTime.parse("2026-10-10T10:00:00")),
                new Dispatch(2,1,LocalDateTime.parse("2026-10-22T10:00:00"))),List.of(),List.of(),List.of(),List.of()),now,90);
        assertEquals(2,report.summary().delivered());
        assertEquals(1,report.summary().humanTotal());
        assertEquals(.5,report.summary().human());
        assertEquals(1,report.summary().autonomous());
        assertEquals(132,report.summary().duration());
        assertEquals(2,report.weeks().stream().mapToLong(w->w.metrics().durationSamples()).sum());
        assertEquals(1,report.weeks().stream().mapToLong(w->w.metrics().humanTotal()).sum());
        assertFalse(report.weeks().getLast().partial());
        assertEquals(Duration.ofDays(7),Duration.between(report.weeks().getLast().start(),report.end()));
    }
    @Test void autonomousRequiresFormalDispatchInTheSameCompletedRoundOnly() {
        var t=now.minusDays(10);
        var events=List.of(
            new Event(1,1,"STATUS_CHANGE","SYSTEM","NEW","DONE",null,t),
            new Event(2,1,"ASSIGN","HUMAN","HUMAN","AGENT",null,t.plusHours(1)),
            new Event(3,1,"STATUS_CHANGE","HUMAN","IN_PROGRESS","DONE",null,t.plusHours(2)),
            new Event(4,1,"STATUS_CHANGE","HUMAN","IN_PROGRESS","DONE",null,t.plusHours(4)),
            new Event(5,1,"STATUS_CHANGE","HUMAN","DONE","IN_PROGRESS",null,t.plusHours(5)),
            new Event(6,1,"STATUS_CHANGE","HUMAN","IN_PROGRESS","DONE",null,t.plusHours(6)));
        var dispatches=List.of(new Dispatch(1,1,t.plusHours(3)),new Dispatch(2,1,t.plusHours(7)));
        var data=new DeliveryAnalyticsData(events,dispatches,List.of(),List.of(),
            List.of(new Entry(1,t.minusHours(1))),List.of());
        var report=new DeliveryAnalyticsCalculator(new MainlandWorkCalendar()).calculate(data,now,30);
        assertEquals(4,report.summary().delivered(),"Non-executed completions remain throughput");
        assertEquals(100,report.summary().resolution(),"Seven-day resolution is unchanged");
        assertEquals(0,report.summary().human(),"Participation denominator is unchanged");
        assertEquals(1,report.summary().autonomous(),"Only the round with its own formal dispatch is autonomous");
        assertEquals(1,report.summary().autonomousRequirements());
        assertEquals(1,report.weeks().stream().mapToLong(w->w.metrics().autonomous()).sum());
    }

    @Test void concurrentTasksSumAndNightHolidayOverlapOnlyOnce() {
        var ds=new ArrayList<Dispatch>();var edges=new ArrayList<Edge>();
        for(int i=1;i<=3;i++){
            var a=LocalDateTime.parse("2026-10-27T01:00:00");
            ds.add(new Dispatch(i,i,a));
            edges.add(new Edge(i*2,i,i,"session.started",a));
            edges.add(new Edge(i*2+1,i,i,"session.completed",a.plusHours(1)));
        }
        var r=new DeliveryAnalyticsCalculator(new MainlandWorkCalendar()).calculate(
            new DeliveryAnalyticsData(List.of(),ds,edges,List.of(),List.of(),List.of()),now,30);
        assertEquals(3,r.summary().workhours());
        assertEquals(3,r.summary().offhours());
        assertEquals(0,r.summary().regularhours());
        assertEquals(3,r.summary().night());
        assertFalse(new MainlandWorkCalendar().isNonWorking(LocalDate.of(2026,10,10)));
        assertTrue(new MainlandWorkCalendar().isNonWorking(LocalDate.of(2026,10,1)));
    }
    @Test void sevenDayCohortUsesEntryAndMaturityAndSkillsIncludeAllTypes() {
        var e=event(1,"STATUS_CHANGE","HUMAN","IN_PROGRESS","DONE","2026-10-22T10:00:00");
        var entries=List.of(new Entry(1,LocalDateTime.parse("2026-10-20T10:00:00")),
            new Entry(2,now.minusDays(1)));
        var skills=List.of(new Skill("MCP",false,2,now.minusDays(10),now),
            new Skill("HOOK",true,3,now.minusDays(10),now.minusDays(2)));
        var r=new DeliveryAnalyticsCalculator(new MainlandWorkCalendar()).calculate(
            new DeliveryAnalyticsData(List.of(e),List.of(),List.of(),List.of(),entries,skills),now,30);
        assertEquals(100,r.summary().resolution());
        assertEquals(1,r.summary().resolutionObserving());
        assertEquals(1,r.summary().skill());
        assertEquals(6,r.summary().versions());
        assertNull(r.summary().duration());
        assertEquals(1,r.summary().durationMissing());
        assertThrows(IllegalArgumentException.class,()->new DeliveryAnalyticsCalculator(new MainlandWorkCalendar()).calculate(
            new DeliveryAnalyticsData(List.of(),List.of(),List.of(),List.of(),List.of(),List.of()),now,7));
    }
    @Test void overlappingSessionsAreUnionedAndReentryIntoExecutionStartsANewRound() {
        var a=now.minusDays(1);
        var edges=List.of(new Edge(1,1,1,"session.started",a,"A"),
            new Edge(2,1,1,"session.forked",a.plusMinutes(30),"B"),
            new Edge(3,1,1,"session.completed",a.plusHours(1),"B"),
            new Edge(4,1,1,"session.completed",a.plusHours(2),"A"));
        var events=List.of(
            new Event(1,1,"STATUS_CHANGE","HUMAN","IN_PROGRESS","DONE",null,a.minusDays(1)),
            new Event(2,1,"STATUS_CHANGE","HUMAN","DONE","IN_PROGRESS",null,a),
            new Event(3,1,"STATUS_CHANGE","HUMAN","IN_PROGRESS","DONE",null,a.plusHours(2)));
        var r=new DeliveryAnalyticsCalculator(new MainlandWorkCalendar()).calculate(
            new DeliveryAnalyticsData(events,List.of(),edges,List.of(),List.of(),List.of()),now,30);
        assertEquals(2,r.summary().workhours());
        assertEquals(1,r.summary().durationSamples());
        assertEquals(2,r.summary().duration());
        assertEquals(0,r.summary().human());
    }

    @Test void calculationHonorsExpiredBudgetAndHandlesLargeWaitingHistoryLinearly() {
        var calculator=new DeliveryAnalyticsCalculator(new MainlandWorkCalendar());
        var empty=new DeliveryAnalyticsData(List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        assertThrows(IllegalStateException.class,()->calculator.calculate(empty,now,90,System.nanoTime()-1));
        var edges=new ArrayList<Edge>();var waits=new ArrayList<Wait>();var a=now.minusDays(2);
        for(int i=0;i<20_000;i++){
            var start=a.plusSeconds(i*3L);
            edges.add(new Edge(i*2L,1,1,"session.started",start,"one"));
            edges.add(new Edge(i*2L+1,1,1,"session.completed",start.plusSeconds(2),"one"));
            waits.add(new Wait(1,start,start.plusSeconds(1)));
        }
        var data=new DeliveryAnalyticsData(List.of(),List.of(),edges,waits,List.of(),List.of());
        assertTimeoutPreemptively(Duration.ofSeconds(10),()->{
            var result=calculator.calculate(data,now,90);
            assertEquals(20_000d/3600,result.summary().workhours(),1e-6);
        });
    }

    @Test void unresolvableHistoricalStatusIsReportedInsteadOfSilentlyClaimingCompleteTotals() {
        var unknown=event(1,"STATUS_CHANGE","HUMAN","IN_PROGRESS",null,"2026-10-22T10:00:00");
        var r=new DeliveryAnalyticsCalculator(new MainlandWorkCalendar()).calculate(
            new DeliveryAnalyticsData(List.of(unknown),List.of(),List.of(),List.of(),List.of(),List.of()),now,30);
        assertEquals(1,r.unclassifiedEvents());
        assertEquals(0,r.summary().delivered());
    }

}
