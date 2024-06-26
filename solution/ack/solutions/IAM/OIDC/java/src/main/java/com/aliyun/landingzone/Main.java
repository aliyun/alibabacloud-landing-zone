package com.aliyun.landingzone;

// com.aliyun:credentials-java >= 0.2.8
import com.aliyun.credentials.Client;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.model.Bucket;
import com.aliyun.sts20150401.models.GetCallerIdentityResponse;

import java.util.List;

class TestOpenAPISDK {

    public void CallAPIWithRRSAOIDCToken(com.aliyun.credentials.Client cred) throws Exception {
        // new client config
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config()
            .setCredential(cred)
            // get endpoint from https://www.alibabacloud.com/help/resource-access-management/latest/endpoints
            .setEndpoint("sts.aliyuncs.com");

        // init client
        com.aliyun.sts20150401.Client client = new com.aliyun.sts20150401.Client(config);

        // call api
        GetCallerIdentityResponse resp = client.getCallerIdentity();
        System.out.println("call sts.GetCallerIdentity via oidc token success:\n");
        System.out.println(resp.getBody().toMap().toString());
    }
}

class OSSOIDCTokenCredentialProvider implements CredentialsProvider {

    private volatile com.aliyun.credentials.Client cred;

    public OSSOIDCTokenCredentialProvider(com.aliyun.credentials.Client cred) {
        this.cred = cred;
    }

    @Override
    public void setCredentials(Credentials creds) {
    }

    @Override
    public Credentials getCredentials() {
        String ak = cred.getAccessKeyId();
        String sk = cred.getAccessKeySecret();
        String token = cred.getSecurityToken();
        return new DefaultCredentials(ak, sk, token);
    }
}

class TestOSSSDK {

    public void CallAPIWithRRSAOIDCToken(com.aliyun.credentials.Client cred) throws Exception {
        // new provider
        OSSOIDCTokenCredentialProvider provider = new OSSOIDCTokenCredentialProvider(cred);
        String endpoint = "https://oss-cn-hangzhou.aliyuncs.com";
        // new client config
        ClientBuilderConfiguration conf = new ClientBuilderConfiguration();

        // init client
        OSS ossClient = new OSSClientBuilder().build(endpoint, provider, conf);

        // call api
        List<Bucket> buckets = ossClient.listBuckets();
        System.out.println("call oss.listBuckets via oidc token success:\n");
        for (Bucket bucket : buckets) {
            System.out.println(" - " + bucket.getName());
        }

        ossClient.shutdown();
    }

}


public class Main {

    public static void main(String[] args) throws Exception {
        // new credential which use rrsa oidc token
        com.aliyun.credentials.models.Config credConf = new com.aliyun.credentials.models.Config();
        credConf.type = "oidc_role_arn";
        credConf.roleArn = System.getenv("ALIBABA_CLOUD_ROLE_ARN");
        credConf.oidcProviderArn = System.getenv("ALIBABA_CLOUD_OIDC_PROVIDER_ARN");
        credConf.oidcTokenFilePath = System.getenv("ALIBABA_CLOUD_OIDC_TOKEN_FILE");
        credConf.roleSessionName = "test-rrsa-oidc-token";
        com.aliyun.credentials.Client cred =  new Client(credConf);

        // test open api sdk (https://github.com/aliyun/alibabacloud-java-sdk) use rrsa oidc token
        System.out.println("\n");
        System.out.println("test open api sdk use rrsa oidc token");
        TestOpenAPISDK openapiSdk = new TestOpenAPISDK();
        openapiSdk.CallAPIWithRRSAOIDCToken(cred);

        // test oss sdk (https://github.com/aliyun/aliyun-oss-java-sdk) use rrsa oidc token
        System.out.println("\n");
        System.out.println("test oss sdk use rrsa oidc token");
        TestOSSSDK osssdk = new TestOSSSDK();
        osssdk.CallAPIWithRRSAOIDCToken(cred);
    }
}