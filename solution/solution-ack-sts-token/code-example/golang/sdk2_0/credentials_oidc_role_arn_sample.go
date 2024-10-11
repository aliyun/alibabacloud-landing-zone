package main

import (
	"fmt"
	"os"

	openapi "github.com/alibabacloud-go/darabonba-openapi/v2/client"
	sts20150401 "github.com/alibabacloud-go/sts-20150401/v2/client"
	util "github.com/alibabacloud-go/tea-utils/v2/service"
	"github.com/alibabacloud-go/tea/tea"
	credentials "github.com/aliyun/credentials-go/credentials"
)

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

	/**
	 * 调用具体业务API，以GetCallerIdentity获取当前调用者身份信息为例
	 */
	stsConfig := &openapi.Config{}
	// 配置云产品服务接入地址（endpoint），以杭州地域为例
	stsConfig.Endpoint = tea.String("sts.cn-hangzhou.aliyuncs.com")
	// 使用Credentials配置凭证
	stsConfig.Credential = credentialClient
	// 初始化STS服务Client
	stsClient, _err := sts20150401.NewClient(stsConfig)
	if _err != nil {
		fmt.Printf("Error creating sts client: %v\n", _err)
		return
	}

	runtime := &util.RuntimeOptions{}
	// 开启自动重试机制，只会对超时等网络异常进行重试
	runtime.Autoretry = tea.Bool(true)
	// 设置自动重试次数
	runtime.MaxAttempts = tea.Int(3)

	resp, tryErr := func() (_result *sts20150401.GetCallerIdentityResponse, _e error) {
		defer func() {
			if r := tea.Recover(recover()); r != nil {
				_e = r
			}
		}()
		_result, _err = stsClient.GetCallerIdentityWithOptions(runtime)
		if _err != nil {
			return nil, _err
		}
		return _result, nil
	}()

	if tryErr != nil {
		// 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常
		// 使用类型断言判断 tryErr 是否为 *tea.SDKError 类型
		if sdkError, ok := tryErr.(*tea.SDKError); ok {
			fmt.Println(tea.StringValue(sdkError.Message))
			fmt.Println(tea.StringValue(sdkError.Code))
			fmt.Println(tea.StringValue(sdkError.Data))
		} else {
			fmt.Println(tryErr.Error())
		}
	} else {
		fmt.Println(resp.Body)
	}
}
