package com.aliyun.autowonder.insights.delivery;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record DeliveryAnalyticsReport(int days, LocalDateTime start,LocalDateTime end,
        Metrics summary,List<Bucket> weeks,int unclosedSessions,long unclassifiedEvents) {
    public record Bucket(LocalDateTime start,LocalDateTime end,boolean partial,Metrics metrics) {}
    public record Metrics(long delivered,Double human,long humanTotal,long humanStartedDeliveries,
        long autonomous,long autonomousRequirements,Double duration,long durationSamples,long durationMissing,
        Double resolution,long resolutionEligible,long resolutionSolved,long resolutionObserving,
        long night,long holiday,double workhours,double offhours,double regularhours,
        long skill,long versions,Map<String,Long> versionTypes) {}
}
