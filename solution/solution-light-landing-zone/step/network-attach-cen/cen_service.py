# -*- coding: utf-8 -*-
import sys
import json

from typing import List
from Tea.core import TeaCore

from alibabacloud_cbn20170912.client import Client as CbnClient
from alibabacloud_cbn20170912 import models as cbn_models
from alibabacloud_tea_console.client import Client as ConsoleClient
from alibabacloud_darabonba_env.client import Client as EnvClient

from alibabacloud_sts20150401.client import Client as Sts20150401Client
from alibabacloud_tea_openapi import models as open_api_models
from alibabacloud_sts20150401 import models as sts_20150401_models
from alibabacloud_tea_util import models as util_models
from alibabacloud_tea_util.client import Client as UtilClient


class Sts:
    def __init__(self):
        pass

    @staticmethod
    def create_client(
            access_key_id: str,
            access_key_secret: str,
    ) -> Sts20150401Client:
        """
        使用AK&SK初始化账号Client
        @param access_key_id:
        @param access_key_secret:
        @return: Client
        @throws Exception
        """
        config = open_api_models.Config(
            # 您的 AccessKey ID,
            access_key_id=access_key_id,
            # 您的 AccessKey Secret,
            access_key_secret=access_key_secret
        )
        # 访问的域名
        config.endpoint = f'sts.cn-shanghai.aliyuncs.com'
        return Sts20150401Client(config)

    @staticmethod
    def assume_rd_role(account_id, access_key_id, access_key_secret):
        client = Sts.create_client(access_key_id, access_key_secret)
        assume_role_request = sts_20150401_models.AssumeRoleRequest(
            role_arn='acs:ram::' + account_id + ':role/ResourceDirectoryAccountAccessRole',
            role_session_name='management-account-programmaticUser'
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.assume_role_with_options(assume_role_request, runtime)
            body_dict = resp.body.to_map()
            return body_dict['Credentials']
        except Exception as error:
            print(UtilClient.assert_as_string(error.message))


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

class CenService:
    def __init__(self):
        pass

    @staticmethod
    def create_cen_client(
            rd_account_id: str,
            access_key_id: str,
            access_key_secret: str
    ) -> CbnClient:
        """
        扮演成员账号角色
        """
        rd_role_credentials = Sts.assume_rd_role(rd_account_id, access_key_id, access_key_secret)

        config = open_api_models.Config()
        config.access_key_id = rd_role_credentials['AccessKeyId']
        config.access_key_secret = rd_role_credentials['AccessKeySecret']
        config.security_token = rd_role_credentials['SecurityToken']

        return CbnClient(config)

    @staticmethod
    def check_transit_router_service(client: CbnClient):
        """
        CheckTransitRouterService 查询当前阿里云账号是否开通转发路由器服务
        """
        req = cbn_models.CheckTransitRouterServiceRequest()
        resp = client.check_transit_router_service(req)
        ConsoleClient.log(UtilClient.to_jsonstring(TeaCore.to_map(resp.body)))
        return TeaCore.to_map(resp.body).get('Enabled')

    @staticmethod
    async def check_transit_router_service_async(
            client: CbnClient,
    ) -> None:
        """
        CheckTransitRouterService 查询当前阿里云账号是否开通转发路由器服务
        """
        req = cbn_models.CheckTransitRouterServiceRequest()
        resp = await client.check_transit_router_service_async(req)
        ConsoleClient.log(UtilClient.to_jsonstring(TeaCore.to_map(resp.body)))
        return

    @staticmethod
    def open_transit_router_service(
            client: CbnClient,
    ) -> None:
        """
        OpenTransitRouterService 开通转发路由器服务
        """
        req = cbn_models.OpenTransitRouterServiceRequest()
        resp = client.open_transit_router_service(req)
        ConsoleClient.log(UtilClient.to_jsonstring(TeaCore.to_map(resp.body)))
        return

    @staticmethod
    async def open_transit_router_service_async(
            client: CbnClient,
    ) -> None:
        """
        OpenTransitRouterService 开通转发路由器服务
        """
        req = cbn_models.OpenTransitRouterServiceRequest()
        resp = await client.open_transit_router_service_async(req)
        ConsoleClient.log(UtilClient.to_jsonstring(TeaCore.to_map(resp.body)))
        return

    @staticmethod
    def open_tr_service(rd_account_id: str):
        client = CenService.create_cen_client(rd_account_id,
                                              EnvClient.get_env('ALICLOUD_ACCESS_KEY'),
                                              EnvClient.get_env('ALICLOUD_SECRET_KEY'))

        ConsoleClient.log('---------查询当前阿里云账号是否开通转发路由器服务----------')
        try:
            is_open = CenService.check_transit_router_service(client)

            if not is_open:
                ConsoleClient.log('-----------------开通转发路由器服务中..--------------------')
                CenService.open_transit_router_service(client)
                ConsoleClient.log('-----------------已开通转发路由器服务--------------------')
            else:
                ConsoleClient.log('-----------------之前已开通转发路由器服务，结束--------------------')
        except:
            ConsoleClient.log('-----------------如果有异常，就强制开通。可能还会报异常--------------------')
            try:
                CenService.open_transit_router_service(client)
            except:
                pass

    @staticmethod
    async def main_async(
            args: List[str],
    ) -> None:
        client = CenService.create_cen_client(EnvClient.get_env('ALICLOUD_ACCESS_KEY'),
                                              EnvClient.get_env('ALICLOUD_SECRET_KEY'))
        ConsoleClient.log('---------查询当前阿里云账号是否开通转发路由器服务----------')
        await CenService.check_transit_router_service_async(client)
        ConsoleClient.log('-----------------开通转发路由器服务--------------------')
        await CenService.open_transit_router_service_async(client)


if __name__ == '__main__':

    '''
    获取共享服务账号ID
    sys.argv[1] = '../../settings.tfvars'
    sys.argv[2] = 'shared_service_account_id'
    '''
    shared_service_account_id = get_config_value(sys.argv[1], sys.argv[2])

    if shared_service_account_id == "" :
        # 从配置文件中获取
        file = open("../var/account.json", "rb")
        account = json.load(file)
        shared_service_account_id = account["shared_service_account_id"].strip()

    if shared_service_account_id != "":
        '''
        共享服务账号中开通转发路由器服务
        '''
        CenService.open_tr_service(shared_service_account_id)
    else:
        print("共享服务账号有问题")

