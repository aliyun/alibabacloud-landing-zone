# -*- coding: utf-8 -*-
import sys
import json

from typing import List

from alibabacloud_vpc20160428.client import Client as Vpc20160428Client
from alibabacloud_tea_openapi import models as open_api_models
from alibabacloud_vpc20160428 import models as vpc_20160428_models
from alibabacloud_tea_util import models as util_models
from alibabacloud_tea_util.client import Client as UtilClient
from alibabacloud_sts20150401.client import Client as Sts20150401Client
from alibabacloud_sts20150401 import models as sts_20150401_models
from alibabacloud_alb20200616.client import Client as Alb20200616Client
from alibabacloud_alb20200616 import models as alb_20200616_models
from alibabacloud_bssopenapi20171214.client import Client as BssOpenApi20171214Client
from alibabacloud_bssopenapi20171214 import models as bss_open_api_20171214_models
from alibabacloud_ecs20140526.client import Client as Ecs20140526Client
from alibabacloud_ecs20140526 import models as ecs_20140526_models

from datetime import timedelta, datetime

rd_management_account_access_key_id = 'yourAccessKeyId'
rd_management_account_access_key_secret = 'yourAccessKeySecret'
rd_management_account_readonly_role_name = 'readOnly'
rd_management_account_id = 'yourRdManagementAccountId'
rd_member_account_id_list = ['yourRdMemberAccountId', 'yourRdMemberAccountId']
region_id_list = ['cn-shanghai', 'cn-hangzhou']


class OpenAPI:
    def __init__(self):
        pass

    @staticmethod
    def create_sts_client(
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
    def create_credentials_by_assume_role(account_id,
                                          access_key_id,
                                          access_key_secret,
                                          role_name):

        client = OpenAPI.create_sts_client(access_key_id,
                                           access_key_secret)

        assume_role_request = sts_20150401_models.AssumeRoleRequest(
            role_arn='acs:ram::' + account_id + ':role/' + role_name,
            role_session_name='management-account-programmaticUser'
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.assume_role_with_options(assume_role_request, runtime)
            body_dict = resp.body.to_map()

            return body_dict['Credentials']
        except Exception as error:
            print(UtilClient.assert_as_string(error))

    @staticmethod
    def create_api_models_config_by_assume_role(account_id,
                                                access_key_id,
                                                access_key_secret,
                                                role_name):

        rd_role_credentials = OpenAPI.create_credentials_by_assume_role(account_id,
                                                                        access_key_id,
                                                                        access_key_secret,
                                                                        role_name)
        config = open_api_models.Config()
        config.access_key_id = rd_role_credentials['AccessKeyId']
        config.access_key_secret = rd_role_credentials['AccessKeySecret']
        config.security_token = rd_role_credentials['SecurityToken']
        return config


class IdleResourceSample:
    def __init__(self):
        pass

    @staticmethod
    def create_ecs_client(
            account_id: str,
            access_key_id: str,
            access_key_secret: str,
            role_name: str,
            region_id: str,
    ) -> Ecs20140526Client:

        config = OpenAPI.create_api_models_config_by_assume_role(account_id,
                                                                 access_key_id,
                                                                 access_key_secret,
                                                                 role_name)
        config.endpoint = 'ecs.{0}.aliyuncs.com'.format(region_id)
        return Ecs20140526Client(config)

    @staticmethod
    def query_ecs_disk(
            region_id: str,
            status: str,
            client: Ecs20140526Client
    ):
        describe_disks_request = ecs_20140526_models.DescribeDisksRequest(
            region_id=region_id,
            status=status
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.describe_disks_with_options(describe_disks_request, runtime)
            body_dict = resp.body.to_map()
            # print(body_dict)
            return body_dict['Disks']['Disk']
        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_idle_ecs_disk(account_id,
                            access_key_id,
                            access_key_secret,
                            role_name):
        idle_resource_list = []
        print('[Idle ECS Disk] AccountId: ' + account_id)
        for region_id in region_id_list:
            output_str = 'Region:' + region_id + ' '
            client = IdleResourceSample.create_ecs_client(account_id,
                                                          access_key_id,
                                                          access_key_secret,
                                                          role_name,
                                                          region_id)
            result_list = IdleResourceSample.query_ecs_disk(region_id, 'Available', client)
            for disk in result_list:
                # 状态为待挂载，闲置
                output_str = output_str + '<ID:{0} Name:{1} Reason:{2} > '.format(disk['DiskId'], disk['DiskName'],
                                                                                  '状态为待挂载')
                idle_resource_list.append(disk)

            print(output_str)
        print()

        return idle_resource_list

    @staticmethod
    def create_bss_client(
            account_id: str,
            access_key_id: str,
            access_key_secret: str,
            role_name: str,
    ) -> BssOpenApi20171214Client:
        config = OpenAPI.create_api_models_config_by_assume_role(account_id,
                                                                 access_key_id,
                                                                 access_key_secret,
                                                                 role_name)
        # 访问的域名
        config.endpoint = f'business.aliyuncs.com'
        return BssOpenApi20171214Client(config)

    @staticmethod
    def query_bss_resource_usage_detail(
            start_period: str,
            end_period: str,
            period_type: str,
            resource_type: str,
            client: BssOpenApi20171214Client,
    ):
        describe_resource_usage_detail_request = bss_open_api_20171214_models.DescribeResourceUsageDetailRequest(
            start_period=start_period,
            end_period=end_period,
            period_type=period_type,
            resource_type=resource_type,
            max_results=300
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.describe_resource_usage_detail_with_options(describe_resource_usage_detail_request, runtime)
            body_dict = resp.body.to_map()
            return body_dict['Data']['Items']
        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_idle_ri(rd_account_id,
                      access_key_id,
                      access_key_secret,
                      role_name):
        client = IdleResourceSample.create_bss_client(rd_account_id,
                                                      access_key_id,
                                                      access_key_secret,
                                                      role_name)

        # 2022-12-27 22:00:00
        today_fixed_time = datetime.today().replace(hour=22, minute=0, second=0, microsecond=0)
        # 2022-12-25 22:00:00
        end_period = today_fixed_time + timedelta(days=-2)
        # 2022-12-25 20:00:00
        start_period = end_period + timedelta(hours=-2)

        ri_list = IdleResourceSample.query_bss_resource_usage_detail(start_period.strftime("%Y-%m-%d %H:%M:%S"),
                                                                     end_period.strftime("%Y-%m-%d %H:%M:%S"),
                                                                     'HOUR', 'RI', client)
        # print(ri_list)
        id_set = set()
        idle_ri_list = []
        usage_percentage_threshold = 0.1
        output_str = '[Idle Reserved Instance Voucher] Region:All\n'

        for ri in ri_list:
            if ri['Status'] != 'Valid':
                continue
            ri_id = ri['ResourceInstanceId']
            if ri_id not in id_set and ri['UsagePercentage'] < usage_percentage_threshold:
                # 查询时段内预留实例券利用率小于阈值，闲置
                output_str = output_str + '<AccountId:{0} ID:{1} Reason:{2} - {3} {4}> '.format(
                    ri['UserId'], ri_id, ri['StartTime'], ri['EndTime'],
                    '使用率低于' + str(usage_percentage_threshold * 100) + '%')

                idle_ri_list.append(ri)
                id_set.add(ri_id)

        print(output_str + '\n')

        return idle_ri_list

    @staticmethod
    def create_alb_client(
            account_id: str,
            access_key_id: str,
            access_key_secret: str,
            role_name: str,
            region_id: str,
    ) -> Alb20200616Client:
        config = OpenAPI.create_api_models_config_by_assume_role(account_id,
                                                                 access_key_id,
                                                                 access_key_secret,
                                                                 role_name)
        config.endpoint = 'alb.{0}.aliyuncs.com'.format(region_id)
        return Alb20200616Client(config)

    @staticmethod
    def query_alb_instance(client: Alb20200616Client):
        list_load_balancers_request = alb_20200616_models.ListLoadBalancersRequest(
            max_results=100
        )

        runtime = util_models.RuntimeOptions()
        try:
            resp = client.list_load_balancers_with_options(list_load_balancers_request, runtime)
            body_dict = resp.body.to_map()
            return body_dict['LoadBalancers']
        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_alb_listener(
            client: Alb20200616Client,
            load_balancer_ids: list,
    ):
        list_listeners_request = alb_20200616_models.ListListenersRequest(
            load_balancer_ids=load_balancer_ids,
            max_results=100
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.list_listeners_with_options(list_listeners_request, runtime)
            body_dict = resp.body.to_map()
            return body_dict['Listeners']
        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_alb_forward_rule(
            client: Alb20200616Client,
            load_balancer_ids: list,
    ):
        list_rules_request = alb_20200616_models.ListRulesRequest(
            load_balancer_ids=load_balancer_ids,
            max_results=100,
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.list_rules_with_options(list_rules_request, runtime)
            body_dict = resp.body.to_map()
            return body_dict['Rules']
        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_alb_server_group(
            client: Alb20200616Client,
            server_group_ids: list,
    ):
        list_server_groups_request = alb_20200616_models.ListServerGroupsRequest(
            server_group_ids=server_group_ids,
            max_results=100,
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.list_server_groups_with_options(list_server_groups_request, runtime)
            body_dict = resp.body.to_map()
            return body_dict['ServerGroups']
        except Exception as error:
            # 如有需要，请打印 error
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_idle_alb(account_id,
                       access_key_id,
                       access_key_secret,
                       role_name):
        idle_resource_list = []
        print('[Idle ALB] AccountId: ' + account_id)
        for region_id in region_id_list:
            output_str = 'Region:' + region_id + ' '
            client = IdleResourceSample.create_alb_client(account_id,
                                                          access_key_id,
                                                          access_key_secret,
                                                          role_name,
                                                          region_id)
            result_list = IdleResourceSample.query_alb_instance(client)
            for alb in result_list:
                alb_id = alb['LoadBalancerId']
                # print(alb_id)
                # 查询监听器
                listeners = IdleResourceSample.query_alb_listener(client, [alb_id])
                # print(listeners)
                if len(listeners) == 0:
                    # 无监听器，闲置
                    output_str = output_str + '<ID:{0} Name:{1} Reason:{2}> '.format(alb_id, alb['LoadBalancerName'],
                                                                                     '无监听器')
                    idle_resource_list.append(alb)
                    continue

                all_listener_stopped = True
                # 转发服务器组ID列表
                forward_server_group_ids = []
                for listener in listeners:
                    # 默认转发规则的服务器组ID
                    forward_server_group_ids.append(
                        listener['DefaultActions'][0]['ForwardGroupConfig']['ServerGroupTuples'][0]['ServerGroupId'])

                    if listener['ListenerStatus'] != 'Stopped':
                        all_listener_stopped = False
                        break

                if all_listener_stopped:
                    # 所有监听器停止，闲置
                    output_str = output_str + '<ID:{0} Name:{1} Reason:{2}> '.format(alb_id, alb['LoadBalancerName'],
                                                                                     ' 所有监听器停止')
                    idle_resource_list.append(alb)
                    continue

                # 查询转发规则
                rules = IdleResourceSample.query_alb_forward_rule(client, [alb_id])
                for rule in rules:
                    # 添加转发规则中的服务器组ID
                    forward_server_group_ids.append(
                        rule['RuleActions'][0]['ForwardGroupConfig']['ServerGroupTuples'][0]['ServerGroupId'])

                # 查询服务器组信息
                server_groups = IdleResourceSample.query_alb_server_group(client, list(set(forward_server_group_ids)))

                all_server_group_is_empty = True
                for server_group in server_groups:
                    if server_group['ServerCount'] > 0:
                        all_server_group_is_empty = False
                        break

                if all_server_group_is_empty:
                    # 所有服务器组都无后端服务器，闲置
                    output_str = output_str + '<ID:{0} Name:{1} Reason:{2}> '.format(alb_id, alb['LoadBalancerName'],
                                                                                     '所有服务器组都无后端服务器')
                    idle_resource_list.append(alb)

            print(output_str)
        print()

        return idle_resource_list

    @staticmethod
    def create_vpc_client(
            account_id,
            access_key_id,
            access_key_secret,
            role_name
    ) -> Vpc20160428Client:
        config = OpenAPI.create_api_models_config_by_assume_role(account_id,
                                                                 access_key_id,
                                                                 access_key_secret,
                                                                 role_name)
        config.endpoint = f'vpc.aliyuncs.com'
        return Vpc20160428Client(config)

    @staticmethod
    def query_common_bandwidth_package(
            client: Vpc20160428Client,
            region_id: str
    ):
        describe_common_bandwidth_packages_request = vpc_20160428_models.DescribeCommonBandwidthPackagesRequest(
            region_id=region_id,
            page_size=50,
            page_number=1
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.describe_common_bandwidth_packages_with_options(describe_common_bandwidth_packages_request,
                                                                          runtime)
            body_dict = resp.body.to_map()
            return body_dict['CommonBandwidthPackages']['CommonBandwidthPackage']
        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_idle_common_bandwidth_package(account_id,
                                            access_key_id,
                                            access_key_secret,
                                            role_name):
        client = IdleResourceSample.create_vpc_client(account_id,
                                                      access_key_id,
                                                      access_key_secret,
                                                      role_name)
        idle_resource_list = []
        print('[Idle Common Bandwidth Package] AccountId: ' + account_id)
        for region_id in region_id_list:
            output_str = 'Region:' + region_id + ' '
            result_list = IdleResourceSample.query_common_bandwidth_package(client, region_id)
            # print(result_list)
            for cbp in result_list:
                if cbp['BusinessStatus'] != 'Normal':
                    continue

                if cbp['PublicIpAddresses'] is None or len(cbp['PublicIpAddresses']['PublicIpAddresse']) == 0:
                    output_str = output_str + '<ID:{0} Name:{1} Reason:{2}> '.format(cbp['BandwidthPackageId'],
                                                                                     cbp['Name'], '未添加EIP')
                    idle_resource_list.append(cbp)
            print(output_str)
        print()

        return idle_resource_list

    @staticmethod
    def query_nat_gateway(client: Vpc20160428Client,
                          region_id: str):
        describe_nat_gateways_request = vpc_20160428_models.DescribeNatGatewaysRequest(
            region_id=region_id,
            page_size=50,
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.describe_nat_gateways_with_options(describe_nat_gateways_request, runtime)
            body_dict = resp.body.to_map()
            # print(body_dict)
            return body_dict['NatGateways']['NatGateway']
        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_nat_gateway_snat_entry(client: Vpc20160428Client,
                                     region_id: str,
                                     nat_gateway_id: str):
        describe_snat_table_entries_request = vpc_20160428_models.DescribeSnatTableEntriesRequest(
            nat_gateway_id=nat_gateway_id,
            region_id=region_id
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.describe_snat_table_entries_with_options(describe_snat_table_entries_request, runtime)
            body_dict = resp.body.to_map()
            # print(body_dict)
            return body_dict['SnatTableEntries']['SnatTableEntry']
        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_nat_gateway_dnat_entry(client: Vpc20160428Client,
                                     region_id: str,
                                     nat_gateway_id: str):
        describe_forward_table_entries_request = vpc_20160428_models.DescribeForwardTableEntriesRequest(
            region_id=region_id,
            nat_gateway_id=nat_gateway_id
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.describe_forward_table_entries_with_options(describe_forward_table_entries_request, runtime)
            body_dict = resp.body.to_map()
            # print(body_dict)
            return body_dict['ForwardTableEntries']['ForwardTableEntry']
        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_idle_nat_gateway(account_id,
                               access_key_id,
                               access_key_secret,
                               role_name):
        client = IdleResourceSample.create_vpc_client(account_id,
                                                      access_key_id,
                                                      access_key_secret,
                                                      role_name)
        idle_resource_list = []
        print('[Idle NAT Gateway] AccountId: ' + account_id)
        for region_id in region_id_list:
            output_str = 'Region:' + region_id + ' '
            result_list = IdleResourceSample.query_nat_gateway(client, region_id)
            # print(eip_list)
            for nat_gateway in result_list:
                if nat_gateway['NetworkType'] == 'internet' and len(nat_gateway['IpLists']['IpList']) == 0:
                    # 公网NAT网关未绑定EIP，闲置
                    output_str = output_str + '<ID:{0} Name:{1} Reason:{2}> '.format(nat_gateway['NatGatewayId'],
                                                                                     nat_gateway['Name'],
                                                                                     '公网NAT网关未绑定EIP')
                    idle_resource_list.append(nat_gateway)
                    continue

                snat_entries = IdleResourceSample.query_nat_gateway_snat_entry(client, region_id,
                                                                               nat_gateway['NatGatewayId'])
                if len(snat_entries) != 0:
                    continue
                else:
                    dnat_entries = IdleResourceSample.query_nat_gateway_dnat_entry(client, region_id,
                                                                                   nat_gateway['NatGatewayId'])
                    if len(dnat_entries) == 0:
                        # 无SNAT及DNAT条目，闲置
                        output_str = output_str + '<ID:{0} Name:{1} Reason:{2}> '.format(nat_gateway['NatGatewayId'],
                                                                                         nat_gateway['Name'],
                                                                                         '无SNAT及DNAT条目')
                        idle_resource_list.append(nat_gateway)
            print(output_str)
        print()

        return idle_resource_list

    @staticmethod
    def query_eip(client: Vpc20160428Client,
                  region_id: str):
        describe_eip_addresses_request = vpc_20160428_models.DescribeEipAddressesRequest(
            region_id=region_id,
            page_size=100,
            page_number=1
        )
        runtime = util_models.RuntimeOptions()
        try:
            resp = client.describe_eip_addresses_with_options(describe_eip_addresses_request, runtime)
            body_dict = resp.body.to_map()
            # print(body_dict)
            return body_dict['EipAddresses']['EipAddress']

        except Exception as error:
            # 如有需要，请打印 error
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_idle_eip(account_id,
                       access_key_id,
                       access_key_secret,
                       role_name):
        client = IdleResourceSample.create_vpc_client(account_id,
                                                      access_key_id,
                                                      access_key_secret,
                                                      role_name)
        idle_eip_list = []
        print('[Idle EIP] AccountId: ' + account_id)
        for region_id in region_id_list:
            output_str = 'Region:' + region_id + ' '
            eip_list = IdleResourceSample.query_eip(client, region_id)
            # print(eip_list)
            for eip in eip_list:
                bind_instance_id = str(eip['InstanceId'])
                if len(bind_instance_id) == 0 or len(bind_instance_id.strip()) == 0:
                    output_str = output_str + '<ID:{0} Name:{1} Reason:{2}> '.format(eip['AllocationId'], eip['Name'],
                                                                                     '未绑定资源')
                    idle_eip_list.append(eip)
            print(output_str)
        print()

        return idle_eip_list

    @staticmethod
    def query_idle_resource():
        rd_member_account_rd_account_access_role = 'ResourceDirectoryAccountAccessRole'

        # EIP
        IdleResourceSample.query_idle_eip(rd_management_account_id,
                                          rd_management_account_access_key_id,
                                          rd_management_account_access_key_secret,
                                          rd_management_account_readonly_role_name)

        for account_id in rd_member_account_id_list:
            IdleResourceSample.query_idle_eip(account_id,
                                              rd_management_account_access_key_id,
                                              rd_management_account_access_key_secret,
                                              rd_member_account_rd_account_access_role)

        # 共享带宽包
        IdleResourceSample.query_idle_common_bandwidth_package(rd_management_account_id,
                                                               rd_management_account_access_key_id,
                                                               rd_management_account_access_key_secret,
                                                               rd_management_account_readonly_role_name)
        for account_id in rd_member_account_id_list:
            IdleResourceSample.query_idle_common_bandwidth_package(account_id,
                                                                   rd_management_account_access_key_id,
                                                                   rd_management_account_access_key_secret,
                                                                   rd_member_account_rd_account_access_role)

        # ALB
        IdleResourceSample.query_idle_alb(rd_management_account_id,
                                          rd_management_account_access_key_id,
                                          rd_management_account_access_key_secret,
                                          rd_management_account_readonly_role_name)
        for account_id in rd_member_account_id_list:
            IdleResourceSample.query_idle_alb(account_id,
                                              rd_management_account_access_key_id,
                                              rd_management_account_access_key_secret,
                                              rd_member_account_rd_account_access_role)

        # NAT网关
        IdleResourceSample.query_idle_nat_gateway(rd_management_account_id,
                                                  rd_management_account_access_key_id,
                                                  rd_management_account_access_key_secret,
                                                  rd_management_account_readonly_role_name)
        for account_id in rd_member_account_id_list:
            IdleResourceSample.query_idle_nat_gateway(account_id,
                                                      rd_management_account_access_key_id,
                                                      rd_management_account_access_key_secret,
                                                      rd_member_account_rd_account_access_role)

        # ECS云盘
        IdleResourceSample.query_idle_ecs_disk(rd_management_account_id,
                                               rd_management_account_access_key_id,
                                               rd_management_account_access_key_secret,
                                               rd_management_account_readonly_role_name)

        for account_id in rd_member_account_id_list:
            IdleResourceSample.query_idle_ecs_disk(account_id,
                                                   rd_management_account_access_key_id,
                                                   rd_management_account_access_key_secret,
                                                   rd_member_account_rd_account_access_role)

        # 预留实例券RI
        IdleResourceSample.query_idle_ri(rd_management_account_id,
                                         rd_management_account_access_key_id,
                                         rd_management_account_access_key_secret,
                                         rd_management_account_readonly_role_name)


if __name__ == '__main__':
    IdleResourceSample.query_idle_resource()
