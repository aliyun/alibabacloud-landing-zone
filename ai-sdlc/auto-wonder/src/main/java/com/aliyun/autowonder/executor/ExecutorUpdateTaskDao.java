package com.aliyun.autowonder.executor;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;

@Mapper
public interface ExecutorUpdateTaskDao {
    void insert(ExecutorUpdateTaskDO task);

    ExecutorUpdateTaskDO findByRequestId(@Param("requestId") String requestId);

    /** The single non-terminal task of one executor, or null; enforces "at most one upgrade in flight". */
    ExecutorUpdateTaskDO findActiveByExecutor(@Param("tenantId") Long tenantId, @Param("executorId") Long executorId);

    /** Newest task per executor, used to render 有新版本/待升级 and the last failure reason in one query. */
    List<ExecutorUpdateTaskDO> listLatestByExecutors(@Param("tenantId") Long tenantId,
                                                     @Param("executorIds") Collection<Long> executorIds);

    /** Non-terminal tasks whose next attempt is due, ordered so the oldest waiter goes first. */
    List<ExecutorUpdateTaskDO> listDeliverable(@Param("now") Date now, @Param("limit") int limit);

    /**
     * How many automatic upgrades of one executor closed as FAILED at or after {@code since}. The sweep
     * and the heartbeat hook both recreate AUTO tasks on their own cadence, so a client that cannot
     * reach the registry would otherwise churn one failed row every couple of minutes forever.
     */
    int countRecentAutoFailures(@Param("tenantId") Long tenantId, @Param("executorId") Long executorId,
                                @Param("since") Date since);

    int updateStatus(@Param("id") Long id, @Param("from") Collection<String> from, @Param("to") String to);

    /** Stamps the send time and the response deadline once the command actually left the server. */
    int markDelivered(@Param("id") Long id, @Param("deliveredAt") Date deliveredAt,
                      @Param("nextAttemptAt") Date nextAttemptAt);

    /**
     * Pushes the response deadline forward without consuming the retry budget, used when the client
     * reports progress or while the executor is simply offline and the task stays 待升级.
     */
    int postpone(@Param("id") Long id, @Param("nextAttemptAt") Date nextAttemptAt);

    /**
     * Records a failed attempt and either schedules a retry (to=PENDING, delivered_at cleared so the
     * sweep re-sends it) or closes the task (to=FAILED).
     */
    int recordFailure(@Param("id") Long id, @Param("lastError") String lastError,
                      @Param("nextAttemptAt") Date nextAttemptAt, @Param("to") String to);
}
