import os
import oss2

from oss2 import CredentialsProvider
from alibabacloud_credentials.client import Client as CredentialsClient
from alibabacloud_credentials.models import Config

def get_credentials_client():
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
    return CredentialsClient(config)

# 使用凭据初始化OSSClient
cred = get_credentials_client()
credentials_provider = CredentialsProvider(cred)
auth = oss2.ProviderAuthV4(credentials_provider)

service = oss2.Service(auth, 'https://oss-cn-hangzhou.aliyuncs.com')

# 列举当前账号下的存储空间。
for b in oss2.BucketIterator(service):
    print(b.name)