import sys

from typing import List

from alibabacloud_sts20150401.client import Client as Sts20150401Client
from alibabacloud_tea_openapi import models as open_api_models
from alibabacloud_tea_util import models as util_models
from alibabacloud_tea_util.client import Client as UtilClient
from alibabacloud_credentials.client import Client as CredClient

class Sample:
    def __init__(self):
        pass

    @staticmethod
    def create_client() -> Sts20150401Client:
        config = open_api_models.Config()
        config.endpoint = f'sts.cn-hangzhou.aliyuncs.com'
        config.credential = CredClient()
        return Sts20150401Client(config)

    @staticmethod
    def main(
        args: List[str],
    ) -> None:
        client = Sample.create_client()
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.get_caller_identity_with_options(runtime)
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
