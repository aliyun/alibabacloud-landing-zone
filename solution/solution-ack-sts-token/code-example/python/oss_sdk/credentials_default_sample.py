import oss2

from oss2 import CredentialsProvider
from alibabacloud_credentials.client import Client as CredentialsClient

# 使用凭据初始化OSSClient
cred = CredentialsClient()
credentials_provider = CredentialsProvider(cred)
auth = oss2.ProviderAuthV4(credentials_provider)

service = oss2.Service(auth, 'https://oss-cn-hangzhou.aliyuncs.com')

# 列举当前账号下的存储空间。
for b in oss2.BucketIterator(service):
    print(b.name)