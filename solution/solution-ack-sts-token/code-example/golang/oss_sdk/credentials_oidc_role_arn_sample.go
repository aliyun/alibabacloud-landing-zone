package main

import (
	"fmt"
	"os"

	"github.com/aliyun/aliyun-oss-go-sdk/oss"
	credentials "github.com/aliyun/credentials-go/credentials"
)

type Credentials struct {
	AccessKeyId     string
	AccessKeySecret string
	SecurityToken   string
}

type CredentialsProvider struct {
	cred credentials.Credential
}

func (credentials *Credentials) GetAccessKeyID() string {
	return credentials.AccessKeyId
}

func (credentials *Credentials) GetAccessKeySecret() string {
	return credentials.AccessKeySecret
}

func (credentials *Credentials) GetSecurityToken() string {
	return credentials.SecurityToken
}

func (defBuild CredentialsProvider) GetCredentials() oss.Credentials {
	cred, _ := defBuild.cred.GetCredential()
	return &Credentials{
		AccessKeyId:     *cred.AccessKeyId,
		AccessKeySecret: *cred.AccessKeySecret,
		SecurityToken:   *cred.SecurityToken,
	}
}

func NewOIDCRoleARNCredentialsProvider(credential credentials.Credential) CredentialsProvider {
	return CredentialsProvider{
		cred: credential,
	}
}

func main() {
	// 使用OIDCRoleArn初始化Credentials Client
	// 建议将Credentials Client作为单例使用，不要每次请求初始化一次，以防内存泄露
	credentialsConfig := new(credentials.Config).
		// 凭证类型
		SetType("oidc_role_arn").
		// OIDC提供商ARN
		SetOIDCProviderArn(os.Getenv("ALIBABA_CLOUD_OIDC_PROVIDER_ARN")).
		// OIDC Token文件路径
		SetOIDCTokenFilePath(os.Getenv("ALIBABA_CLOUD_OIDC_TOKEN_FILE")).
		// RAM角色名称ARN
		SetRoleArn(os.Getenv("ALIBABA_CLOUD_ROLE_ARN")).
		// 角色会话名称，请根据需要自行修改
		SetRoleSessionName("<RoleSessionName>").
		// 设置更小的权限策略，非必填，请根据需要自行修改。示例值：{"Statement": [{"Action": ["*"],"Effect": "Allow","Resource": ["*"]}],"Version":"1"}
		SetPolicy("<Policy>").
		// 设置session过期时间
		SetRoleSessionExpiration(3600)

	credentialClient, _err := credentials.NewCredential(credentialsConfig)
	if _err != nil {
		fmt.Printf("Error creating credential client: %v\n", _err)
		return
	}

	provider := NewOIDCRoleARNCredentialsProvider(credentialClient)
	// 填写Bucket所在地域对应的Endpoint。以华东1（杭州）为例，Endpoint填写为https://oss-cn-hangzhou.aliyuncs.com。
	endpoint := "https://oss-cn-hangzhou.aliyuncs.com"
	// 填写Endpoint对应的Region信息，例如cn-hangzhou。
	region := "cn-hangzhou"
	// 推荐使用更安全的V4签名算法。使用V4签名初始化时，除指定Endpoint以外，您还需要指定阿里云通用Region ID作为发起请求地域的标识
	// OSS Go SDK 3.0.2及以上版本支持V4签名
	authVersion := oss.AuthVersion(oss.AuthV4)
	// 建议将OSS Client作为单例使用，不要每次请求初始化一次，以防内存泄露
	ossClient, _err := oss.New(endpoint, "", "", oss.SetCredentialsProvider(&provider), authVersion, oss.Region(region))
	if _err != nil {
		fmt.Printf("Error creating oss client: %v\n", _err)
		return
	}

	// 以列举当前账号下的存储空间为例
	bucketList, _err := ossClient.ListBuckets(oss.MaxKeys(20))
	if _err != nil {
		fmt.Printf("Error listing buckets: %v\n", _err)
		return
	}
	for _, bucket := range bucketList.Buckets {
		fmt.Printf("Bucket: %s\n", bucket.Name)
	}
}
