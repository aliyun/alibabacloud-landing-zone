package com.aliyun.autowonder.im;

public interface ImProvider {
    String provider();

    void send(ImSendCommand command);
}
