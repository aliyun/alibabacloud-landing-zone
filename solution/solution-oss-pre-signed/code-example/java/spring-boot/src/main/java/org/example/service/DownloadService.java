package org.example.service;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import org.example.config.OssConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.Date;

@Service
public class DownloadService {

    @Autowired
    OssConfig ossConfig;

    @Autowired
    OSS ossClient;

    /**
     * 生成客户端可以直接下载的签名 url，只支持单个文件的下载
     * 本示例，未区分调用者身份，允许调用用户从同一个固定的 OSS 文件夹下下载文件
     * 您可以根据调用者身份，使用调用者的登录信息等唯一标识作为 OSS 文件夹名称，保证不同调用者和不同文件夹绑定并且只允许从与其绑定的文件夹下下载文件，以此隔离调用用户的资源和权限
     * 请根据实际的业务场景，自行调整修改
     *
     * @param fileName
     * @return
     */
    public String generateSignedUrl(String fileName) {
        long expireEndTime = System.currentTimeMillis() + ossConfig.getDownloadExpireTime() * 1000;
        Date expiration = new Date(expireEndTime);

        // 生成签名 url
        // 本示例，未区分调用者身份，允许调用用户从同一个固定的 OSS 文件夹下下载文件
        // 您可以根据调用者身份，使用调用者的登录信息等唯一标识作为 OSS 文件夹名称，保证不同调用者和不同文件夹绑定并且只允许从与其绑定的文件夹下下载文件，以此隔离调用用户的资源和权限
        GeneratePresignedUrlRequest generatePresignedUrlRequest = new GeneratePresignedUrlRequest(ossConfig.getBucket(), ossConfig.getDir() + fileName, HttpMethod.GET);
        // 设置过期时间。
        generatePresignedUrlRequest.setExpiration(expiration);

        try {
            // 通过HTTP GET请求生成签名URL。
            URL signedUrl = ossClient.generatePresignedUrl(generatePresignedUrlRequest);

            return signedUrl.toString();
        } catch (ClientException e) {
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
            System.out.println("Caught an ClientException, which means the client encountered "
                + "a serious internal problem while trying to communicate with OSS, "
                + "such as not being able to access the network.");
            System.out.println(e.getMessage());
            throw e;
        }
    }
}
