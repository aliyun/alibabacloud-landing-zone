import os

from alibabacloud_sts20150401.client import Client as Sts20150401Client
from alibabacloud_tea_openapi import models as open_api_models
from alibabacloud_tea_util import models as util_models
from alibabacloud_credentials.client import Client as CredentialsClient
from alibabacloud_credentials.models import Config

# 使用OIDCRoleArn
config = Config(
    type='oidc_role_arn',
    access_key_id=os.environ.get('ALIBABA_CLOUD_ACCESS_KEY_ID'),
    access_key_secret=os.environ.get('ALIBABA_CLOUD_ACCESS_KEY_SECRET'),
    security_token=os.environ.get('ALIBABA_CLOUD_SECURITY_TOKEN'),
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

config = open_api_models.Config()
config.endpoint = f'sts.cn-hangzhou.aliyuncs.com'
config.credential = cred
stsClient = Sts20150401Client(config)

try:
    runtime = util_models.RuntimeOptions()
    # 复制代码运行请自行打印 API 的返回值
    response = stsClient.get_caller_identity_with_options(runtime)
    print(response)
except Exception as error:
    print(error)