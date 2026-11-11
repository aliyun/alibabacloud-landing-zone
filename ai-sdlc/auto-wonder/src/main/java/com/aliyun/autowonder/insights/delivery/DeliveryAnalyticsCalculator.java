package com.aliyun.autowonder.insights.delivery;

import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import static com.aliyun.autowonder.insights.delivery.DeliveryAnalyticsData.*;
import static com.aliyun.autowonder.insights.delivery.DeliveryAnalyticsReport.*;

@Component
public class DeliveryAnalyticsCalculator {
    private final MainlandWorkCalendar calendar;
    static final Set<String> STARTS=Set.of("agent.started","session.started","session.resumed","session.forked");
    static final Set<String> ENDS=Set.of("session.completed","session.failed","session.interrupted",
        "session.cancelled","dispatch.completed","dispatch.failed","dispatch.cancelled");
    record Round(long id,LocalDateTime start,LocalDateTime end,long human,boolean formallyStarted) {
        Double hours(){return start==null?null:Duration.between(start,end).toMillis()/3600000d;}
    }
    record Span(long workitemId,LocalDateTime start,LocalDateTime end) {}
    record Runtime(List<Span> spans,List<Edge> activity,int unclosed) {}
    public DeliveryAnalyticsCalculator(MainlandWorkCalendar calendar){this.calendar=calendar;}

    private final ThreadLocal<Long> deadline=new ThreadLocal<>();
    public DeliveryAnalyticsReport calculate(DeliveryAnalyticsData data,LocalDateTime now,int days) {
        return calculate(data,now,days,System.nanoTime()+Duration.ofSeconds(90).toNanos());
    }
    public DeliveryAnalyticsReport calculate(DeliveryAnalyticsData data,LocalDateTime now,int days,long limit) {
        deadline.set(limit);
        try { checkBudget(); return calculateWithinBudget(data,now,days); }
        finally { deadline.remove(); }
    }
    private void checkBudget() {
        Long limit=deadline.get();
        if(Thread.currentThread().isInterrupted()||(limit!=null&&System.nanoTime()>limit))
            throw new IllegalStateException("BUILD_TIMEOUT");
    }
    private List<Span> merge(List<Span> input) {
        var sorted=input.stream().filter(p->p.end.isAfter(p.start)).sorted(Comparator.comparing(Span::start)).toList();
        List<Span> result=new ArrayList<>();
        for(var span:sorted){
            checkBudget();
            if(result.isEmpty()||result.getLast().end.isBefore(span.start)){result.add(span);continue;}
            var prev=result.removeLast();
            result.add(new Span(span.workitemId,prev.start,prev.end.isAfter(span.end)?prev.end:span.end));
        }
        return result;
    }
    private DeliveryAnalyticsReport calculateWithinBudget(DeliveryAnalyticsData data,LocalDateTime now,int days) {
        if(!Set.of(30,60,90).contains(days))throw new IllegalArgumentException("Unsupported period");
        var start=now.minusDays(days-1).toLocalDate().atStartOfDay();
        if(!calendar.supports(start.toLocalDate(),now.toLocalDate()))throw new IllegalStateException("CALENDAR_UNAVAILABLE");
        var rounds=rounds(data);
        var completed=rounds.stream().filter(r->inside(r.end,start,now)).toList();
        var runtime=runtime(data,now);
        List<Bucket> buckets=new ArrayList<>();
        for(var end=now;end.isAfter(start);) {
            var left=end.minusDays(7).isBefore(start)?start:end.minusDays(7);
            buckets.add(new Bucket(left,end,Duration.between(left,end).compareTo(Duration.ofDays(7))<0,
                metrics(data,rounds,completed,runtime,left,end,now,false)));
            end=left;
        }
        Collections.reverse(buckets);
        return new DeliveryAnalyticsReport(days,start,now,metrics(data,rounds,completed,runtime,start,now,now,true),
            List.copyOf(buckets),runtime.unclosed,data.events().stream().filter(e->"STATUS_CHANGE".equals(e.type())&&e.to()==null
                &&!"MIGRATION".equals(e.source())).map(Event::id).distinct().count());
    }
    List<Round> rounds(DeliveryAnalyticsData data) {
        Map<Long,List<Event>> byWork=new HashMap<>();
        data.events().forEach(e->byWork.computeIfAbsent(e.workitemId(),k->new ArrayList<>()).add(e));
        data.dispatches().forEach(d->byWork.computeIfAbsent(d.workitemId(),k->new ArrayList<>())
            .add(new Event(-d.id(),d.workitemId(),"START","SYSTEM",null,null,null,d.at())));
        List<Round> result=new ArrayList<>();
        for(var events:byWork.values()){
            checkBudget();
            events.sort(Comparator.comparing(Event::at).thenComparingLong(Event::id));
            LocalDateTime start=null;long handoffs=0;boolean formallyStarted=false;Set<Long> seen=new HashSet<>();
            for(var e:events){
                checkBudget();
                if(!seen.add(e.id()))continue;
                if("STATUS_CHANGE".equals(e.type())&&"MIGRATION".equals(e.source()))continue;
                boolean done="STATUS_CHANGE".equals(e.type())&&"DONE".equals(e.to())&&!"DONE".equals(e.from());
                if(done){result.add(new Round(e.workitemId(),start,e.at(),Math.max(0,handoffs-1),formallyStarted));start=null;handoffs=0;formallyStarted=false;continue;}
                // START is synthesized only from formal WORKITEM dispatches loaded by the repository.
                // Assignment/status changes alone cannot prove that delivery was started.
                if("START".equals(e.type()))formallyStarted=true;
                boolean began="START".equals(e.type())||("ASSIGN".equals(e.type())&&"AGENT".equals(e.to()))
                    ||("STATUS_CHANGE".equals(e.type())&&"IN_PROGRESS".equals(e.to()));
                if(start==null&&began)start=e.at();
                if("ASSIGN".equals(e.type())&&"AGENT".equals(e.actor())&&"AGENT".equals(e.from())&&"HUMAN".equals(e.to()))
                    handoffs++;
            }
        }
        return result;
    }
    Runtime runtime(DeliveryAnalyticsData data,LocalDateTime now) {

        Map<Long,List<Edge>> byDispatch=new HashMap<>();
        data.edges().stream().filter(e->e.at()!=null&&e.at().isBefore(now)).forEach(e->byDispatch.computeIfAbsent(e.dispatchId(),k->new ArrayList<>()).add(e));
        Map<Long,List<Span>> waits=new HashMap<>();
        data.waits().forEach(w->waits.computeIfAbsent(w.dispatchId(),k->new ArrayList<>()).add(new Span(0,w.start(),w.end())));
        waits.replaceAll((id,list)->merge(list));
        List<Span> spans=new ArrayList<>();List<Edge> activity=new ArrayList<>();int unclosed=0;
        for(var entry:byDispatch.entrySet()){
            checkBudget();
            var edges=entry.getValue();edges.sort(Comparator.comparing(Edge::at).thenComparingLong(Edge::id));
            boolean sessionEvidence=edges.stream().anyMatch(e->e.type().startsWith("session.")&&STARTS.contains(e.type()));
            Map<String,LocalDateTime> active=new HashMap<>();Set<Long> seen=new HashSet<>();
            List<Span> closed=new ArrayList<>();
            for(var e:edges){
                checkBudget();
                if(!seen.add(e.id()))continue;
                if(STARTS.contains(e.type())||"turn.started".equals(e.type()))activity.add(e);
                // Agent start is the legacy fallback only; it must not keep a finished session open.
                if("agent.started".equals(e.type())&&sessionEvidence)continue;
                String session=e.session()==null||e.session().isBlank()?"legacy":e.session();
                if(STARTS.contains(e.type()))active.putIfAbsent(session,e.at());
                if(ENDS.contains(e.type())){
                    if(e.type().startsWith("dispatch.")){
                        active.values().forEach(start->closed.add(new Span(e.workitemId(),start,e.at())));
                        active.clear();
                    }else{
                        var start=active.remove(session);
                        if(start!=null)closed.add(new Span(e.workitemId(),start,e.at()));
                    }
                }
            }
            unclosed+=active.size();
            // Per-task union, then linear interval subtraction. Concurrent tasks remain additive.
            var merged=merge(closed);var waiting=waits.getOrDefault(entry.getKey(),List.of());int cursor=0;
            for(var span:merged){
                checkBudget();
                var left=span.start;
                while(cursor<waiting.size()&&!waiting.get(cursor).end.isAfter(left))cursor++;
                int j=cursor;
                while(j<waiting.size()&&waiting.get(j).start.isBefore(span.end)){
                    checkBudget();var wait=waiting.get(j);
                    if(wait.start.isAfter(left))spans.add(new Span(span.workitemId,left,wait.start));
                    if(wait.end.isAfter(left))left=wait.end;
                    if(!left.isBefore(span.end))break;
                    j++;
                }
                cursor=j;
                if(left.isBefore(span.end))spans.add(new Span(span.workitemId,left,span.end));
            }
        }
        return new Runtime(spans,activity,unclosed);
    }
    Metrics metrics(DeliveryAnalyticsData data,List<Round> all,List<Round> cohort,Runtime run,
                    LocalDateTime a,LocalDateTime b,LocalDateTime now,boolean summary) {
        var done=cohort.stream().filter(r->inside(r.end,a,b)).toList();
        var started=summary?done:cohort.stream().filter(r->r.start!=null&&inside(r.start,a,b)).toList();
        var measured=started.stream().filter(r->r.hours()!=null).toList();
        long human=started.stream().mapToLong(Round::human).sum();
        Map<Long,List<LocalDateTime>> completions=new HashMap<>();
        all.forEach(r->completions.computeIfAbsent(r.id,k->new ArrayList<>()).add(r.end));
        long eligible=0,solved=0,observing=0;
        for(var e:data.entries()){
            checkBudget();
            if(!inside(e.at(),a,b))continue;
            var due=e.at().plusDays(7);
            if(due.isAfter(now)){observing++;continue;}
            eligible++;
            if(completions.getOrDefault(e.workitemId(),List.of()).stream().anyMatch(t->!t.isBefore(e.at())&&!t.isAfter(due)))solved++;
        }
        Set<Long> night=new HashSet<>(),holiday=new HashSet<>();
        for(var e:run.activity)if(inside(e.at(),a,b)){
            if(e.at().getHour()<9||e.at().getHour()>=21)night.add(e.workitemId());
            if(calendar.isNonWorking(e.at().toLocalDate()))holiday.add(e.workitemId());
        }
        double seconds=0,off=0;
        for(var s:run.spans){
            checkBudget();
            var left=s.start.isAfter(a)?s.start:a;var right=s.end.isBefore(b)?s.end:b;
            while(left.isBefore(right)){
                checkBudget();
                var day=left.toLocalDate();var mid=day.atStartOfDay();var stop=right.isBefore(mid.plusDays(1))?right:mid.plusDays(1);
                double elapsed=Duration.between(left,stop).toMillis()/1000d;seconds+=elapsed;
                boolean nonworking=calendar.isNonWorking(day);
                if(nonworking){holiday.add(s.workitemId);off+=elapsed;}
                else{
                    var l=left.isAfter(mid.plusHours(9))?left:mid.plusHours(9);
                    var r=stop.isBefore(mid.plusHours(21))?stop:mid.plusHours(21);
                    off+=elapsed-Math.max(0,Duration.between(l,r).toMillis()/1000d);
                }
                if(left.isBefore(mid.plusHours(9))||stop.isAfter(mid.plusHours(21)))night.add(s.workitemId);
                left=stop;
            }
        }
        long stock=0;Map<String,Long> versions=new TreeMap<>();
        for(var s:data.skills()){
            checkBudget();
            if(s.created().isBefore(b)&&(!s.deleted()||!s.modified().isBefore(b)))stock++;
            versions.merge(s.type(),Math.max(1L,(long)s.version()+1-(s.deleted()?1:0)),Long::sum);
        }
        return new Metrics(done.size(),started.isEmpty()?null:(double)human/started.size(),human,started.size(),
            done.stream().filter(r->r.human==0&&r.formallyStarted).count(),done.stream().filter(r->r.human==0&&r.formallyStarted).map(Round::id).distinct().count(),
            measured.isEmpty()?null:measured.stream().mapToDouble(r->r.hours()).average().orElseThrow(),measured.size(),started.size()-measured.size(),
            eligible==0?null:100d*solved/eligible,eligible,solved,observing,night.size(),holiday.size(),
            seconds/3600,off/3600,(seconds-off)/3600,stock,versions.values().stream().mapToLong(Long::longValue).sum(),Map.copyOf(versions));
    }
    static boolean inside(LocalDateTime t,LocalDateTime a,LocalDateTime b){return !t.isBefore(a)&&t.isBefore(b);}
}
