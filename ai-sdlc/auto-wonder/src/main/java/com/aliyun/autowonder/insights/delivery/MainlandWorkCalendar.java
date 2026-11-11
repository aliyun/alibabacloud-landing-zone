package com.aliyun.autowonder.insights.delivery;

import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.*;

/** Published mainland calendar; unsupported years fail explicitly instead of inventing holiday totals.
 * Source: https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm
 */
@Component
public class MainlandWorkCalendar {
    private final Set<LocalDate> holidays=new HashSet<>(), working=new HashSet<>();
    public MainlandWorkCalendar() {
        for(String range:List.of("2026-01-01/2026-01-03","2026-02-15/2026-02-23",
                "2026-04-04/2026-04-06","2026-05-01/2026-05-05","2026-06-19/2026-06-21",
                "2026-09-25/2026-09-27","2026-10-01/2026-10-07")) {
            var p=range.split("/"); var end=LocalDate.parse(p[1]);
            for(var d=LocalDate.parse(p[0]);!d.isAfter(end);d=d.plusDays(1)) holidays.add(d);
        }
        for(String d:List.of("2026-01-04","2026-02-14","2026-02-28","2026-05-09","2026-09-20","2026-10-10"))
            working.add(LocalDate.parse(d));
    }
    public boolean supports(LocalDate start,LocalDate end) { return start.getYear()==2026&&end.getYear()==2026; }
    public boolean isNonWorking(LocalDate day) {
        if(!supports(day,day)) throw new IllegalStateException("CALENDAR_UNAVAILABLE");
        return !working.contains(day)&&(holidays.contains(day)||day.getDayOfWeek().getValue()>=6);
    }
}
