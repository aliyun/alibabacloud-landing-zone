package com.aliyun.autowonder.insights.delivery;

import javax.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Cache contains aggregates only; bounded two-worker builds never execute on HTTP threads. */
@Service
public class DeliveryAnalyticsService {
    private static final Logger log=LoggerFactory.getLogger(DeliveryAnalyticsService.class);
    private final DeliveryAnalyticsRepository repository;
    private final DeliveryAnalyticsCalculator calculator;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"delivery-insights");t.setDaemon(true);return t;},
        new ThreadPoolExecutor.AbortPolicy());
    private final Map<Long,State> cache=new LinkedHashMap<>(16,.75f,true);
    static class State { boolean building;long expires;Map<Integer,DeliveryAnalyticsReport> reports;String error; }
    public record Response(String status,DeliveryAnalyticsReport report,String errorCode) {}
    public DeliveryAnalyticsService(DeliveryAnalyticsRepository repository,DeliveryAnalyticsCalculator calculator){this.repository=repository;this.calculator=calculator;}
    public synchronized Response get(long tenant,int days) {
        if(tenant<=0||!Set.of(30,60,90).contains(days))throw new IllegalArgumentException("Invalid analytics scope");
        var state=cache.get(tenant);long current=System.currentTimeMillis();
        if(state==null){
            if(cache.size()>=128){
                var idle=cache.entrySet().stream().filter(e->!e.getValue().building).findFirst();
                if(idle.isEmpty())return new Response("BUSY",null,null);
                cache.remove(idle.get().getKey());
            }
            state=new State();cache.put(tenant,state);
        }
        if(!state.building&&current>=state.expires){
            state.building=true;State target=state;
            try {workers.execute(()->build(tenant,target));}
            catch(RejectedExecutionException full){state.building=false;state.expires=current+5000;state.error="BUSY";}
        }
        if(state.reports!=null)return new Response(state.building?"REFRESHING":"READY",state.reports.get(days),state.error);
        return new Response(state.building?"COMPUTING":state.error==null?"BUSY":"ERROR",null,state.error);
    }
    private void build(long tenant,State state){
        try{
            long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
            var now=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            var data=repository.load(tenant,now);
            Map<Integer,DeliveryAnalyticsReport> reports=new HashMap<>();
            for(int days:List.of(30,60,90))reports.put(days,calculator.calculate(data,now,days,deadline));
            if(System.nanoTime()>deadline)throw new IllegalStateException("BUILD_TIMEOUT");
            synchronized(this){state.reports=Map.copyOf(reports);state.error=null;state.expires=System.currentTimeMillis()+300_000;}
        }catch(Exception ex){
            log.warn("Delivery analytics build failed tenant={}",tenant,ex);
            synchronized(this){state.error="CALENDAR_UNAVAILABLE".equals(ex.getMessage())?"CALENDAR_UNAVAILABLE":"BUILD_FAILED";state.expires=System.currentTimeMillis()+60_000;}
        }finally{synchronized(this){state.building=false;}}
    }
    @PreDestroy public void close(){workers.shutdownNow();}
}
