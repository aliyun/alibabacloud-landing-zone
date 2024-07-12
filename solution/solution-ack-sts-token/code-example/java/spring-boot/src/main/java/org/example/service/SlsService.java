package org.example.service;

import com.alibaba.fastjson2.JSON;
import com.aliyun.openservices.log.exception.LogException;
import com.aliyun.openservices.log.response.ListProjectResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SlsService {

    @Autowired
    com.aliyun.openservices.log.Client slsClient;

    /**
     * 调用SLS API，以调用ListProject获取SLS Project列表为例
     */
    public String listProjects() {
        try {
            ListProjectResponse listProjectResponse = slsClient.ListProject();
            return JSON.toJSONString(listProjectResponse);
        } catch (LogException e) {
            e.printStackTrace();
            return null;
        }
    }
}
