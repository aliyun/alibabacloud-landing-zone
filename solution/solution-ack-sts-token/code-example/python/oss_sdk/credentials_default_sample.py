import oss2

from oss2 import CredentialsProvider
from oss2.credentials import Credentials
from alibabacloud_credentials.client import Client as CredentialsClient

class CredentialProviderWarpper(CredentialsProvider):
    def __init__(self, client):
        self.client = client

    def get_credentials(self):
        access_key_id = self.client.get_access_key_id()
        access_key_secret = self.client.get_access_key_secret()
        security_token = self.client.get_security_token()
        return Credentials(access_key_id, access_key_secret, security_token)

# 使用凭据初始化OSSClient
cred = CredentialsClient()
credentials_provider = CredentialProviderWarpper(cred)

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