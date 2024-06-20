import os

from aliyun.log import LogClient
from aliyun.log.credentials import Credentials
from alibabacloud_credentials.client import Client as CredentialsClient
from alibabacloud_credentials.models import Config

def get_credentials():
    # 初始化默认凭据链方式的Credentials客户端
    config = Config(
        type='oidc_role_arn',
        access_key_id=os.environ.get('ALIBABA_CLOUD_ACCESS_KEY_ID'),
        access_key_secret=os.environ.get('ALIBABA_CLOUD_ACCESS_KEY_SECRET'),
        security_token=os.environ.get('ALIBABA_CLOUD_SECURITY_TOKEN'),
        role_arn=os.environ.get('ALIBABA_CLOUD_ROLE_ARN'),
        oidc_provider_arn=os.environ.get('ALIBABA_CLOUD_OIDC_PROVIDER_ARN'),
        oidc_token_file_path=os.environ.get('ALIBABA_CLOUD_OIDC_TOKEN_FILE'),
        # 角色会话名称，如果配置了ALIBABA_CLOUD_ROLE_SESSION_NAME这个环境变量，则无需设置
        role_session_name='<RoleSessionName>',
        # 设置更小的权限策略，非必填。示例值：{"Statement": [{"Action": ["*"],"Effect": "Allow","Resource": ["*"]}],"Version":"1"}
        policy='<Policy>',
        # 设置session过期时间
        role_session_expiration=3600
    )
    cred = CredentialsClient(config)
    # 获取凭据
    cloud_credential = cred.cloud_credential
    access_key_id = cloud_credential.get_access_key_id()
    access_key_secret = cloud_credential.get_access_key_secret()
    security_token = cloud_credential.get_security_token()
    # 返回构造的Credentials对象
    return Credentials(access_key_id, access_key_secret, security_token)

# 使用凭据初始化LogClient
credentials = get_credentials()
client = LogClient("cn-hangzhou-intranet.log.aliyuncs.com", credentials)
# 获取项目列表
response = client.list_project()
# 打印响应
print(response.body)