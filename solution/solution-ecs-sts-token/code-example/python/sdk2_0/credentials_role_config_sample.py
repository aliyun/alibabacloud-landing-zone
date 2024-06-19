import sys

from typing import List

from alibabacloud_vpc20160428.client import Client as Vpc20160428Client
from alibabacloud_tea_openapi import models as open_api_models
from alibabacloud_vpc20160428 import models as vpc_20160428_models
from alibabacloud_tea_util import models as util_models
from alibabacloud_tea_util.client import Client as UtilClient
from alibabacloud_credentials.client import Client as CredClient
from alibabacloud_credentials.models import Config as CredConfig

class Sample:
    def __init__(self):
        pass

    @staticmethod
    def create_client() -> Vpc20160428Client:
        credConfig = CredConfig(
            type='ecs_ram_role',
            role_name='my-ecs-role',
            enable_imds_v2=True
        )
        config = open_api_models.Config()
        config.endpoint = f'vpc-vpc.cn-hangzhou.aliyuncs.com'
        config.credential = CredClient(credConfig)
        return Vpc20160428Client(config)

    @staticmethod
    def main(
        args: List[str],
    ) -> None:
        client = Sample.create_client()
        describe_vpcs_request = vpc_20160428_models.DescribeVpcsRequest(
            region_id='cn-hangzhou'
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.describe_vpcs_with_options(describe_vpcs_request, runtime)
            print(resp)
        except Exception as error:
            # 此处仅做打印展示，请谨慎对待异常处理，在工程项目中切勿直接忽略异常。
            # 错误 message
            print(error.message)
            # 诊断地址
            print(error.data.get("Recommend"))
            UtilClient.assert_as_string(error.message)

if __name__ == '__main__':
    Sample.main(sys.argv[1:])
