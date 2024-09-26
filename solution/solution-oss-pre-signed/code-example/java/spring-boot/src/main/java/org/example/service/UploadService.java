package org.example.service;

import com.alibaba.fastjson2.JSON;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.utils.BinaryUtil;
import com.aliyun.oss.model.MatchMode;
import com.aliyun.oss.model.PolicyConditions;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.example.config.OssConfig;
import org.example.model.OssPostCallback;
import org.example.model.PostCallbackResp;
import org.example.model.PostSignatureResp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Date;

@Service
public class UploadService {

    @Autowired
    OssConfig ossConfig;

    @Autowired
    OSS ossClient;

    @Autowired
    CredentialsProvider ossCredentialsProvider;

    /**
     * 生成客户端直传所需要的 Post Policy 和 Post Signature
     * 本示例，未区分调用者身份，允许调用用户上传文件到同一个固定的 OSS 文件夹下
     * 您可以根据调用者身份，使用调用者的登录信息等唯一标识作为 OSS 文件夹名称，保证不同调用者和不同文件夹绑定并且只允许上传文件到与其绑定的文件夹下，以此隔离调用用户的资源和权限
     * 请根据实际的业务场景，自行调整修改
     *
     * @return
     */
    public PostSignatureResp generatePostSignature() {
        try {
            long expireEndTime = System.currentTimeMillis() + ossConfig.getUploadExpireTime() * 1000;
            Date expiration = new Date(expireEndTime);

            // 构造 OSS Policy
            // 更多 Policy，请参考：https://help.aliyun.com/zh/oss/developer-reference/signature-version-4-recommend#49c0713824yc9
            PolicyConditions policyConds = new PolicyConditions();
            // 限制上传文件的大小
            policyConds.addConditionItem(PolicyConditions.COND_CONTENT_LENGTH_RANGE, 0, 1048576000);
            // 限制 OSS Object 名称。这里通过 starts-with 指定前缀，限制上传文件所在的目录
            // 本示例，未区分调用者身份，允许调用用户上传文件到同一个固定的 OSS 文件夹下
            // 您可以根据调用者身份，使用调用者的登录信息等唯一标识作为 OSS 文件夹名称，保证不同调用者和不同文件夹绑定并且只允许上传文件到与其绑定的文件夹下，以此隔离调用用户的资源和权限
            policyConds.addConditionItem(MatchMode.StartWith, PolicyConditions.COND_KEY, ossConfig.getDir());

            String postPolicy = ossClient.generatePostPolicy(expiration, policyConds);
            byte[] binaryData = postPolicy.getBytes("utf-8");
            String encodedPolicy = BinaryUtil.toBase64String(binaryData);
            String postSignature = ossClient.calculatePostSignature(postPolicy);

            // 构造 OSS Callback 配置
            // 客户端上传完后，OSS 会根据 Callback 配置，将上传后的文件信息回调给服务端
            // 如果您的业务场景不需要感知客户端上传结果，可以忽略此段回调配置的代码
            OssPostCallback ossPostCallback = OssPostCallback.builder()
                .callbackUrl(ossConfig.getPostCallbackUrl())
                // callbackBody 支持 OSS 系统参数、自定义变量和常量
                // 更多参数配置，请参考：https://help.aliyun.com/zh/oss/developer-reference/callback#a8a8e930e31fv
                .callbackBody(
                    "filename=${object}"
                        + "&size=${size}"
                        + "&mimeType=${mimeType}"
                        + "&height=${imageInfo.height}"
                        + "&width=${imageInfo.width}"
                )
                .callbackBodyType("application/x-www-form-urlencoded")
                .build();
            String base64PostCallback = BinaryUtil.toBase64String(JSON.toJSONString(ossPostCallback).getBytes());

            PostSignatureResp postSignatureResp = PostSignatureResp.builder()
                .accessKeyId(ossCredentialsProvider.getCredentials().getAccessKeyId())
                .securityToken(ossCredentialsProvider.getCredentials().getSecurityToken())
                .policy(encodedPolicy)
                .signature(postSignature)
                .dir(ossConfig.getDir())
                .host(ossConfig.getHost())
                .expires(String.valueOf(expireEndTime / 1000))
                .callback(base64PostCallback)
                .build();

            return postSignatureResp;
        } catch (OSSException e) {
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
            System.out.println("Caught an OSSException, which means your request made it to OSS, "
                + "but was rejected with an error response for some reason.");
            // 打印错误码
            System.out.println(e.getErrorCode());
            // 打印错误信息
            System.out.println(e.getMessage());
            // 打印 RequestId
            System.out.println(e.getRequestId());
            throw e;
        } catch (ClientException e) {
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
            System.out.println("Caught an ClientException, which means the client encountered "
                + "a serious internal problem while trying to communicate with OSS, "
                + "such as not being able to access the network.");
            System.out.println(e.getMessage());
            throw e;
        } catch (Exception e) {
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * OSS 回调请求处理，需要验证回调请求的合法性
     * 回调中返回的响应结果，OSS 会将此响应内容返回给客户端
     * 请根据实际业务场景，自行修改返回内容
     *
     * @param callbackBody  回调请求 Body
     * @param request   回调请求
     * @return
     */
    public ResponseEntity<PostCallbackResp> callback(String callbackBody, HttpServletRequest request) {
        HttpStatus status = HttpStatus.OK;
        PostCallbackResp postCallbackResp = new PostCallbackResp();
        try {
            Boolean verification = verifyOssCallback(callbackBody, request);
            if (verification) {
                postCallbackResp.setSuccess(true);
                postCallbackResp.setMessage("Upload successfully.");
            } else {
                status = HttpStatus.BAD_REQUEST;
                postCallbackResp.setSuccess(false);
                postCallbackResp.setMessage("OSS callback verification failed.");
            }
        } catch (Exception e) {
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
            status = HttpStatus.BAD_REQUEST;
            postCallbackResp.setSuccess(false);
            postCallbackResp.setMessage("A system error occurred.");
        }

        return ResponseEntity.status(status).body(postCallbackResp);
    }

    /**
     * 验证 OSS 回调请求的合法性
     *
     * @param callbackBody
     * @param request
     * @return
     */
    private Boolean verifyOssCallback(String callbackBody, HttpServletRequest request)
        throws UnsupportedEncodingException {
        String autorizationInput = request.getHeader("Authorization");
        String pubKeyInput = request.getHeader("x-oss-pub-key-url");
        byte[] authorization = BinaryUtil.fromBase64String(autorizationInput);
        byte[] pubKey = BinaryUtil.fromBase64String(pubKeyInput);
        String pubKeyAddr = new String(pubKey);
        if (!pubKeyAddr.startsWith("http://gosspublic.alicdn.com/")
            && !pubKeyAddr.startsWith("https://gosspublic.alicdn.com/")) {
            System.out.println("Pub key addr must be oss addrss.");
            return false;
        }
        String retString = executeGet(pubKeyAddr);
        retString = retString.replace("-----BEGIN PUBLIC KEY-----", "");
        retString = retString.replace("-----END PUBLIC KEY-----", "");
        String queryString = request.getQueryString();
        String uri = request.getRequestURI();
        String decodeUri = java.net.URLDecoder.decode(uri, "UTF-8");
        String authStr = decodeUri;
        if (queryString != null && !queryString.equals("")) {
            authStr += "?" + queryString;
        }
        authStr += "\n" + callbackBody;
        return doCheck(authStr, authorization, retString);
    }

    /**
     * 获取 public key
     *
     * @param url
     * @return
     * @throws IOException
     */
    private String executeGet(String url) {
        try {
            CloseableHttpClient httpClient = HttpClients.custom().build();
            HttpGet httpGet = new HttpGet(url);
            CloseableHttpResponse response = httpClient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                return EntityUtils.toString(entity);
            }
        } catch (Exception e) {
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
        }
        return "";
    }

    /**
     * 验证 RSA
     *
     * @param content
     * @param sign
     * @param publicKey
     * @return
     */
    private Boolean doCheck(String content, byte[] sign, String publicKey) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            byte[] encodedKey = BinaryUtil.fromBase64String(publicKey);
            PublicKey pubKey = keyFactory.generatePublic(new X509EncodedKeySpec(encodedKey));
            java.security.Signature signature = java.security.Signature.getInstance("MD5withRSA");
            signature.initVerify(pubKey);
            signature.update(content.getBytes());
            return signature.verify(sign);
        } catch (Exception e) {
            // 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            e.printStackTrace();
        }
        return false;
    }
}
