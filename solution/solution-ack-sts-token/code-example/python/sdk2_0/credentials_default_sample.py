from alibabacloud_vpc20160428.client import Client as Vpc20160428Client
from alibabacloud_tea_openapi import models as open_api_models
from alibabacloud_vpc20160428 import models as vpc_20160428_models
from alibabacloud_tea_util import models as util_models
from alibabacloud_credentials.client import Client as CredentialsClient

# 使用默认凭据链
cred = CredentialsClient()

config = open_api_models.Config()
config.endpoint = f'vpc-vpc.cn-hangzhou.aliyuncs.com'
config.credential = cred
vpcClient = Vpc20160428Client(config)

describeVpcsRequest = vpc_20160428_models.DescribeVpcsRequest(region_id='cn-hangzhou')

try:
    runtime = util_models.RuntimeOptions()
    # 复制代码运行请自行打印 API 的返回值
    describeVpcsResponse = vpcClient.describe_vpcs(describeVpcsRequest, runtime)
    print(describeVpcsResponse.body.vpcs.vpc)
except Exception as error:
    print(error)