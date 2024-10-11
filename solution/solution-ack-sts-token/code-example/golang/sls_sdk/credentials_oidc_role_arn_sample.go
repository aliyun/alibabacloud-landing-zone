package main

import (
	"fmt"
	"os"

	sls "github.com/aliyun/aliyun-log-go-sdk"
	credentials "github.com/aliyun/credentials-go/credentials"
)

type CredentialsProvider struct {
	cred credentials.Credential
}

func (provider *CredentialsProvider) GetCredentials() (sls.Credentials, error) {
	cred, _ := provider.cred.GetCredential()
	return sls.Credentials{
		AccessKeyID:     *cred.AccessKeyId,
		AccessKeySecret: *cred.AccessKeySecret,
		SecurityToken:   *cred.SecurityToken,
	}, nil
}

func NewOIDCRoleARNCredentialsProvider(credential credentials.Credential) *CredentialsProvider {
	return &CredentialsProvider{
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

	// 日志服务的服务入口。此处以杭州为例，其它地域请根据实际情况填写。
	endpoint := "cn-hangzhou.log.aliyuncs.com"
	slsClient := sls.CreateNormalInterfaceV2(endpoint, provider)

	// 以列举当前账号下的project为例
	projectNames, _err := slsClient.ListProject()
	if _err != nil {
		fmt.Printf("Error listing projects: %v\n", _err)
		return
	}
	for _, projectName := range projectNames {
		fmt.Printf("Project: %s\n", projectName)
	}
}
