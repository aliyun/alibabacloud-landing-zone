import os
import oss2

from oss2 import CredentialsProvider
from oss2.credentials import Credentials
from alibabacloud_credentials.client import Client as CredentialsClient
from alibabacloud_credentials.models import Config

class CredentialProviderWrapper(CredentialsProvider):
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
    # 角色会话名称
    role_session_name='<RoleSessionName>',
    # 设置更小的权限策略，非必填。示例值：{"Statement": [{"Action": ["*"],"Effect": "Allow","Resource": ["*"]}],"Version":"1"}
    policy='<Policy>',
    # 设置session过期时间
    role_session_expiration=3600
)
cred = CredentialsClient(config)

# 使用凭据初始化OSSClient
credentials_provider = CredentialProviderWrapper(cred)

# 填写Bucket所在地域对应的Endpoint。以华东1（杭州）为例，Endpoint填写为https://oss-cn-hangzhou.aliyuncs.com。
endpoint = 'https://oss-cn-hangzhou.aliyuncs.com'
# 填写Endpoint对应的Region信息，例如cn-hangzhou。
region = 'cn-hangzhou'
# 推荐使用更安全的V4签名算法。使用V4签名初始化时，除指定Endpoint以外，您还需要指定阿里云通用Region ID作为发起请求地域的标识
# OSS Python SDK 2.18.4及以上版本支持V4签名。
auth = oss2.ProviderAuthV4(credentials_provider)

service = oss2.Service(auth, endpoint, region=region)

# 列举当前账号下的存储空间。
for b in oss2.BucketIterator(service):
    print(b.name)