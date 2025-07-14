# -*- coding: utf-8 -*-
import sys
import json

from Tea.core import TeaCore

from alibabacloud_tea_console.client import Client as ConsoleClient
from alibabacloud_darabonba_env.client import Client as EnvClient
from alibabacloud_tag20180828.client import Client as Tag20180828Client
from alibabacloud_tag20180828 import models as tag_20180828_models

from alibabacloud_tea_openapi import models as open_api_models
from alibabacloud_tea_util import models as util_models
from alibabacloud_tea_util.client import Client as UtilClient


def parse_json(content):
    try:
        return json.loads(content)
    except Exception as e:
        return None


def get_config_value(file_path, key):
    config_value = ''
    with open(file_path, 'r') as f:
        line = f.readline()
        while True:
            if not line:
                break

            if not line.startswith('#') and key in line:
                line_kv_list = line.split('=')
                config_value = line_kv_list[1].strip().strip('"').strip('\'')
                break

            line = f.readline()

    print(config_value)
    return config_value

class TagService:
    def __init__(self):
        pass

    @staticmethod
    def create_tag_client(
            access_key_id: str,
            access_key_secret: str,
    ) -> Tag20180828Client:
        config = open_api_models.Config(
            # 必填，您的 AccessKey ID,
            access_key_id=access_key_id,
            # 必填，您的 AccessKey Secret,
            access_key_secret=access_key_secret
        )
        # 访问的域名
        config.endpoint = f'tag.aliyuncs.com'
        return Tag20180828Client(config)

    @staticmethod
    def open_create_tag_service(
            region: str,
            client: Tag20180828Client,
    ) -> None:
        """
        OpenCreatedBy 开通创建者标签服务
        """
        req = tag_20180828_models.OpenCreatedByRequest(
            region_id=region
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.open_created_by_with_options(req, runtime)
            ConsoleClient.log(UtilClient.to_jsonstring(TeaCore.to_map(resp.body)))
        except Exception as error:
            # 如有需要，请打印 error
            UtilClient.assert_as_string(error.message)
        return

    @staticmethod
    def open_tag_policy_service(
            region: str,
            client: Tag20180828Client,
    ) -> None:
        """
        EnablePolicyType 开通标签策略服务
        """
        req = tag_20180828_models.EnablePolicyTypeRequest(region_id=region)

        runtime = util_models.RuntimeOptions()
        try:
            resp = client.enable_policy_type_with_options(req, runtime)
            ConsoleClient.log(UtilClient.to_jsonstring(TeaCore.to_map(resp.body)))
        except Exception as error:
            # 如有需要，请打印 error
            UtilClient.assert_as_string(error.message)
        return


    @staticmethod
    def open_tag_service(region: str):
        client = TagService.create_tag_client(EnvClient.get_env('ALICLOUD_ACCESS_KEY'),
                                              EnvClient.get_env('ALICLOUD_SECRET_KEY'))
        ConsoleClient.log('-----------------开通创建者标签服务中..--------------------')
        TagService.open_create_tag_service(region, client)
        ConsoleClient.log('-----------------已开通创建者标签服务--------------------')

        ConsoleClient.log('-----------------开通标签策略服务中..--------------------')
        TagService.open_tag_policy_service(region, client)
        ConsoleClient.log('-----------------已开通标签策略服务--------------------')




if __name__ == '__main__':

    '''
    获取Region
    sys.argv[1] = '../../settings.tfvars'
    sys.argv[2] = 'light_landingzone_region'
    '''
    light_landingzone_region = get_config_value(sys.argv[1], sys.argv[2])

    '''
    共享服务账号中开通转发路由器服务
    '''
    TagService.open_tag_service(light_landingzone_region)
