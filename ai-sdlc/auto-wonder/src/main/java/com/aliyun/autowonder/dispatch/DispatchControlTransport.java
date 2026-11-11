package com.aliyun.autowonder.dispatch;

public interface DispatchControlTransport {
    void pause(DispatchDO dispatch);

    // Legacy transports retain their pause behavior; WebSocket transports can
    // distinguish terminal cancellation without changing the frame type.
    default void stop(DispatchDO dispatch) { pause(dispatch); }
}
