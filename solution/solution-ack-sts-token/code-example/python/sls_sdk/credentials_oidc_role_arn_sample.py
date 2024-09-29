import os

from aliyun.log import LogClient
from aliyun.log.credentials import CredentialsProvider, Credentials
from alibabacloud_credentials.client import Client as CredentialsClient
from alibabacloud_credentials.models import Config

class CredentialProviderWarpper(CredentialsProvider):
    def __init__(self, client):
        self.client = client

    def get_credentials(self):
        credential = self.client.get_credential()
        access_key_id = credential.access_key_id
        access_key_secret = credential.access_key_secret
        security_token = credential.security_token
        return Credentials(access_key_id, access_key_secret, security_token)

# 初始化Credentials客户端
# 请确保Credentials Python SDK（alibabacloud-credentials）版本>=0.3.5
config = Config(
    type='oidc_role_arn',
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

credentials_provider=CredentialProviderWarpper(cred)

# 使用凭据初始化LogClient
client = LogClient("cn-hangzhou.log.aliyuncs.com", credentials_provider=credentials_provider)
# 获取项目列表
response = client.list_project()
# 打印响应
print(response.body)