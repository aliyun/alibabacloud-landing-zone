from aliyun.log import LogClient
from aliyun.log.credentials import CredentialsProvider, Credentials

from alibabacloud_credentials.client import Client as CredentialsClient

class ECSRoleCredentialDemo(CredentialsProvider):
    def __init__(self, client):
        self.client = client

    def get_credentials(self):
        cred = self.client.get_credential()
        access_key_id = cred.get_access_key_id()
        access_key_secret = cred.get_access_key_secret()
        security_token = cred.get_security_token()
        return Credentials(access_key_id, access_key_secret, security_token)


# 默认凭据链方式初始化Credentials客户端
# 请确保Credentials Python SDK（alibabacloud-credentials）版本>=0.3.5
cred = CredentialsClient()

credentials_provider=ECSRoleCredentialDemo(cred)
client = LogClient("cn-hangzhou-intranet.log.aliyuncs.com", credentials_provider=credentials_provider)

response = client.list_project()
print(response.body)