package com.aliyun.autowonder.insights.participation;

/** 强制刷新是否被受理，以及未受理时的具体原因。 */
public enum ParticipationRefreshResult {
    ACCEPTED,
    LOCK_CONTENTION,
    QUEUE_FULL
}
