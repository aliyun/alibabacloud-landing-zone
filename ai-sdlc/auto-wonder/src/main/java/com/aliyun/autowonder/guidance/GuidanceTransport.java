package com.aliyun.autowonder.guidance;

public interface GuidanceTransport {
    void send(GuidanceDO guidance, String contentMd);
}
