# -*- coding: utf-8 -*-
import oss2
from alibabacloud_credentials.client import Client as CredClient
from alibabacloud_credentials.models import Config as CredConfig
from oss2 import CredentialsProvider
from oss2.credentials import Credentials


class ECSRoleCredentialDemo(CredentialsProvider):
    def __init__(self, client):
        self.client = client

    def get_credentials(self):
        cred = self.client.cloud_credential
        access_key_id = cred.get_access_key_id()
        access_key_secret = cred.get_access_key_secret()
        security_token = cred.get_security_token()
        return Credentials(access_key_id, access_key_secret, security_token)


# 默认凭据链方式初始化Credentials客户端
cred = CredClient()

credentials_provider = ECSRoleCredentialDemo(cred)
auth = oss2.ProviderAuthV4(credentials_provider)
service = oss2.Service(auth, 'https://oss-cn-hangzhou.aliyuncs.com')

# 列举当前账号下的存储空间。
for b in oss2.BucketIterator(service):
    print(b.name)