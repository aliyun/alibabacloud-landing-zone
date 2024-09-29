from alibabacloud_sts20150401.client import Client as Sts20150401Client
from alibabacloud_tea_openapi import models as open_api_models
from alibabacloud_tea_util import models as util_models
from alibabacloud_credentials.client import Client as CredentialsClient

# 使用默认凭据链
# 请确保Credentials Python SDK（alibabacloud-credentials）版本>=0.3.5
cred = CredentialsClient()

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