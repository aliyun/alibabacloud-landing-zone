package org.example.service;

import com.alibaba.fastjson2.JSON;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.Bucket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OssService {

    @Autowired
    OSS ossClient;

    /**
     * 调用OSS API，以调用ListBuckets获取OSS Bucket列表为例
     */
    public String listBuckets() {
        List<Bucket> buckets = ossClient.listBuckets();
        return JSON.toJSONString(buckets);
    }
}
