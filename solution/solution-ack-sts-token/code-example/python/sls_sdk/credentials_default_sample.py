from aliyun.log import LogClient
from aliyun.log.credentials import CredentialsProvider, Credentials
from alibabacloud_credentials.client import Client as CredentialsClient

class CredentialProviderWarpper(CredentialsProvider):
    def __init__(self, client):
        self.client = client

    def get_credentials(self):
        credential = self.client.get_credential()
        access_key_id = credential.access_key_id
        access_key_secret = credential.access_key_secret
        security_token = credential.security_token
        return Credentials(access_key_id, access_key_secret, security_token)

# 默认凭据链方式初始化Credentials客户端
# 请确保Credentials Python SDK（alibabacloud-credentials）版本>=0.3.5
cred = CredentialsClient()

credentials_provider=CredentialProviderWarpper(cred)

# 使用凭据初始化LogClient
client = LogClient("cn-hangzhou.log.aliyuncs.com", credentials_provider=credentials_provider)
# 获取项目列表
response = client.list_project()
# 打印响应
print(response.body)
