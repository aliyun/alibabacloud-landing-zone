from aliyun.log import LogClient
from aliyun.log.credentials import Credentials
from alibabacloud_credentials.client import Client as CredentialsClient

def get_credentials():
    # 初始化默认凭据链方式的Credentials客户端
    cred = CredentialsClient()
    # 获取凭据
    cloud_credential = cred.cloud_credential
    access_key_id = cloud_credential.get_access_key_id()
    access_key_secret = cloud_credential.get_access_key_secret()
    security_token = cloud_credential.get_security_token()
    # 返回构造的Credentials对象
    return Credentials(access_key_id, access_key_secret, security_token)

# 获取凭据
credentials = get_credentials()
# 使用凭据初始化LogClient
client = LogClient("cn-hangzhou-intranet.log.aliyuncs.com", credentials)
# 获取项目列表
response = client.list_project()
# 打印响应
print(response.body)
