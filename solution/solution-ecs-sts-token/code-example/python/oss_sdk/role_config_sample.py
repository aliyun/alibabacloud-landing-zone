# -*- coding: utf-8 -*-
import oss2
from alibabacloud_credentials.client import Client
from alibabacloud_credentials.models import Config
from oss2 import CredentialsProvider
from oss2.credentials import Credentials
import os


class CredentialProviderWrapper(CredentialsProvider):
    def __init__(self, client):
        self.client = client

    def get_credentials(self):
        access_key_id = self.client.get_access_key_id()
        access_key_secret = self.client.get_access_key_secret()
        security_token = self.client.get_security_token()
        return Credentials(access_key_id, access_key_secret, security_token)


config = Config(
    type='ecs_ram_role',      # 访问凭证类型。固定为ecs_ram_role。
    role_name='my-ecs-role'   # 为ECS授予的RAM角色的名称。可选参数。如果不设置，将自动检索。强烈建议设置，以减少请求。
)

cred = Client(config)

credentials_provider = CredentialProviderWrapper(cred)

auth = oss2.ProviderAuthV4(credentials_provider)
service = oss2.Service(auth, 'https://oss-cn-hangzhou.aliyuncs.com', region='cn-hangzhou')

# 列举当前账号下的存储空间。
for b in oss2.BucketIterator(service):
    print(b.name)