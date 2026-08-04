package com.aliyun.autowonder.log.metric;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.*;

import com.aliyun.autowonder.configuration.ThreadPoolManager;
import com.aliyun.openservices.aliyun.log.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aliyun.openservices.aliyun.log.producer.Result;
import com.aliyun.openservices.aliyun.log.producer.errors.ProducerException;
import com.aliyun.openservices.log.common.LogItem;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricAttribute;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Slf4jReporter;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;


public class MetricReporter extends ScheduledReporter {

    private Logger logger = LoggerFactory.getLogger(getClass());

    public static Builder forRegistry(MetricRegistry registry) {
        return new Builder(registry);
    }

    public static class Builder {
        private final MetricRegistry registry;
        private String prefix;
        private TimeUnit rateUnit;
        private TimeUnit durationUnit;
        private MetricFilter filter;
        private ScheduledExecutorService executor;
        private boolean shutdownExecutorOnStop;
        private Set<MetricAttribute> disabledMetricAttributes;
        private Producer producer;
        private String project;
        private String logstore;
        private TreeMap<String, String> defaultLabels;

        private Builder(MetricRegistry registry) {
            this.registry = registry;
            this.prefix = "";
            this.rateUnit = TimeUnit.SECONDS;
            this.durationUnit = TimeUnit.MILLISECONDS;
            this.filter = MetricFilter.ALL;
            this.executor = null;
            this.shutdownExecutorOnStop = true;
            this.disabledMetricAttributes = Collections.emptySet();
            this.defaultLabels = new TreeMap<>();
        }

        public Builder shutdownExecutorOnStop(boolean shutdownExecutorOnStop) {
            this.shutdownExecutorOnStop = shutdownExecutorOnStop;
            return this;
        }

        public Builder scheduleOn(ScheduledExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public Builder prefixedWith(String prefix) {
            this.prefix = prefix;
            return this;
        }

        public Builder convertRatesTo(TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        public Builder convertDurationsTo(TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        public Builder filter(MetricFilter filter) {
            this.filter = filter;
            return this;
        }

        public Builder disabledMetricAttributes(Set<MetricAttribute> disabledMetricAttributes) {
            this.disabledMetricAttributes = disabledMetricAttributes;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder logstore(String logstore) {
            this.logstore = logstore;
            return this;
        }

        public Builder producer(Producer producer) {
            this.producer = producer;
            return this;
        }

        public Builder defaultLabels(TreeMap<String, String> defaultLabels) {
            this.defaultLabels = defaultLabels;
            return this;
        }

        public MetricReporter build() {
            return new MetricReporter(
                    registry,
                    prefix,
                    rateUnit,
                    durationUnit,
                    filter,
                    executor,
                    shutdownExecutorOnStop,
                    disabledMetricAttributes,
                    producer,
                    project,
                    logstore,
                    defaultLabels
            );
        }

    }

    private final String prefix;

    private TreeMap<String, String> defaultLabels;

    private MetricReporter(MetricRegistry registry,
                           String prefix,
                           TimeUnit rateUnit,
                           TimeUnit durationUnit,
                           MetricFilter filter,
                           ScheduledExecutorService executor,
                           boolean shutdownExecutorOnStop,
                           Set<MetricAttribute> disabledMetricAttributes,
                           Producer producer,
                           String project,
                           String logstore, TreeMap<String, String> defaultLabels) {
        super(registry, "autowonder-metrics-reporter", filter, rateUnit, durationUnit, executor, shutdownExecutorOnStop,
                disabledMetricAttributes);
        this.prefix = prefix;
        this.producer = producer;
        this.project = project;
        this.logstore = logstore;
        this.defaultLabels = defaultLabels;
    }

    private Producer producer;
    private String project;
    private String logstore;

    @Override
    @SuppressWarnings("rawtypes")
    public void report(SortedMap<String, Gauge> gauges,
                       SortedMap<String, Counter> counters,
                       SortedMap<String, Histogram> histograms,
                       SortedMap<String, Meter> meters,
                       SortedMap<String, Timer> timers) {
        gauges.forEach((name, gauge) -> {
            ObjectName objectName = getObjectName(name);
            sendMetric(objectName, objectName.getPrefix(), gauge.getValue());
        });
        counters.forEach((name, counter) -> {
            ObjectName objectName = getObjectName(name);
            sendMetric(objectName, objectName.getPrefix(), counter.getCount());
        });
        timers.forEach((name, timer) -> {
            ObjectName objectName = getObjectName(name);
            Snapshot s = timer.getSnapshot();
            sendMetric(objectName, "rt_" + objectName.getPrefix() + "_min", convertDuration(s.getMin()));
            sendMetric(objectName, "rt_" + objectName.getPrefix() + "_max", convertDuration(s.getMax()));
            sendMetric(objectName, "rt_" + objectName.getPrefix() + "_mean", convertDuration(s.getMean()));
            sendMetric(objectName, "rt_" + objectName.getPrefix() + "_stddev", convertDuration(s.getStdDev()));
            sendMetric(objectName, "rt_" + objectName.getPrefix() + "_median", convertDuration(s.getMedian()));
            sendMetric(objectName, "rt_" + objectName.getPrefix() + "_p99", convertDuration(s.get99thPercentile()));
            sendMetric(objectName, "rt_" + objectName.getPrefix() + "_p999", convertDuration(s.get999thPercentile()));
            sendMetric(objectName, "rate_" + objectName.getPrefix() + "_m1", convertRate(timer.getOneMinuteRate()));
        });
        meters.forEach((name, meter) -> {
            ObjectName objectName = getObjectName(name);
            sendMetric(objectName, "rate_" + objectName.getPrefix() + "_m1", convertRate(meter.getOneMinuteRate()));
        });
        histograms.forEach((name, histogram) -> {
            ObjectName objectName = getObjectName(name);
            Snapshot s = histogram.getSnapshot();
            sendMetric(objectName, objectName.getPrefix() + "_min", s.getMin());
            sendMetric(objectName, objectName.getPrefix() + "_max", s.getMax());
            sendMetric(objectName, objectName.getPrefix() + "_mean", s.getMean());
            sendMetric(objectName, objectName.getPrefix() + "_stddev", s.getStdDev());
            sendMetric(objectName, objectName.getPrefix() + "_median", s.getMedian());
            sendMetric(objectName, objectName.getPrefix() + "_p99", s.get99thPercentile());
            sendMetric(objectName, objectName.getPrefix() + "_p999", s.get999thPercentile());
        });
        logger.info("report metrics success. gauges: {}, counters: {}, timers: {}, meters: {}, histograms: {}",
                gauges.size(), counters.size(), timers.size(), meters.size(), histograms.size());
    }

    private ObjectName getObjectName(String name) {
        ObjectName on = new ObjectName();
        on.getLabels().putAll(this.defaultLabels);
        on.setOriginName(name);
        String[] strs = name.split(":");
        if (strs.length >= 2) {
            String m = strs[0];
            on.setPrefix(m);
            String[] kvList = strs[1].split(",");
            for (String kv : kvList) {
                String[] kvp = kv.split("=");
                if (kvp.length >= 2) {
                    on.getLabels().put(kvp[0], kvp[1]);
                }
            }
        } else {
            on.setPrefix(name);
        }
        return on;
    }

    private LoadingCache<ObjectName, String> labelsCache = CacheBuilder.newBuilder()
            .concurrencyLevel(8)
            .expireAfterWrite(8, TimeUnit.SECONDS)
            .refreshAfterWrite(1, TimeUnit.SECONDS)
            .initialCapacity(20)
            .maximumSize(500)
            .build(
                    new CacheLoader<ObjectName, String>() {
                        @Override
                        public String load(ObjectName objectName) throws Exception {
                            return objectName.toLabelsString();
                        }
                    }
            );

    private String getLabels(ObjectName objectName) {
        try {
            return labelsCache.get(objectName);
        } catch (ExecutionException e) {
            logger.warn("getLabels error.", e.getCause());
            return objectName.toLabelsString();
        }
    }

    private void sendMetric(ObjectName objectName, String name, Object value) {
        if (producer == null) {
            logger.debug("Producer is null, skipping metric: {}", name);
            return;
        }

        LogItem log = new LogItem();
        log.PushBack("__name__", this.prefix(name).replaceAll("\\.|\\-", "_").toLowerCase());
        log.PushBack("__time_nano__", String.valueOf(System.currentTimeMillis() / 1000));
        log.PushBack("__labels__", getLabels(objectName));
        log.PushBack("__value__", String.valueOf(value));
        log.PushBack("namespace", "autowonder");
        try {
            ListenableFuture<Result> f = producer.send(project, logstore, log);
            Futures.addCallback(f, new FutureCallback<Result>() {
                @Override
                public void onSuccess(Result result) {

                }
                @Override
                public void onFailure(Throwable t) {
                    logger.warn("send metrics onFailure, name: {}", name, t);
                }
            }, ThreadPoolManager.metricCallBackExecutor);
        } catch (InterruptedException | ProducerException e) {
            logger.warn("report error, name: {}", name, e);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("log accumulator was closed")) {
                logger.debug("LogAccumulator closed, stop sending metric: {}", name);
            } else {
                logger.warn("IllegalStateException when sending metric, name: {}", name, e);
            }
        } catch (Exception e) {
            logger.warn("Unknown error when sending metric, name: {}", name, e);
        }
    }

    @Override
    protected String getRateUnit() {
        return "events/" + super.getRateUnit();
    }

    private String prefix(String... components) {
        return MetricRegistry.name(prefix, components);
    }

    static class ObjectName {

        private String prefix;

        private SortedMap<String, String> labels = new TreeMap<>();

        private String name;

        public String getName() {
            return name;
        }

        public void setOriginName(String name) {
            this.name = name;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getPrefix() {
            return prefix;
        }

        public Map<String, String> getLabels() {
            return labels;
        }

        public String toLabelsString() {
            StringBuilder sb = new StringBuilder();
            labels.forEach((k, v) -> {
                sb.append(k).append("#$#").append(v).append('|');
            });
            if (sb.charAt(sb.length() - 1) == '|') {
                sb.deleteCharAt(sb.length() - 1);
            }
            return sb.toString();
        }

        @Override
        public int hashCode() {
            return this.name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return this.name.equals(obj);
        }

    }
}
