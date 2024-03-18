# -*- coding: utf-8 -*-
import traceback
from alibabacloud_tea_openapi import models as open_api_models
from alibabacloud_tea_util import models as util_models
from alibabacloud_tea_util.client import Client as UtilClient
from alibabacloud_sts20150401.client import Client as Sts20150401Client
from alibabacloud_sts20150401 import models as sts_20150401_models

from alibabacloud_sls20201230.client import Client as Sls20201230Client
from alibabacloud_sls20201230 import models as sls_20201230_models

from alibabacloud_oss20190517.client import Client as Oss20190517Client
from alibabacloud_oss20190517 import models as oss_20190517_models
from Tea.exceptions import TeaException

rd_management_account_access_key_id = 'yourAccessKeyId'
rd_management_account_access_key_secret = 'yourAccessKeySecret'
rd_management_account_readonly_role_name = 'readOnly'
rd_management_account_id = 'yourRdManagementAccountId'
rd_member_account_id_list = ['yourRdMemberAccountId', 'yourRdMemberAccountId']
region_id_list = ['cn-shanghai', 'cn-hangzhou']

# SLS存储天数阀值，LogStore存储天数大于该值则视为风险项
sls_ttl_threshold_days = 90

# SLS热存储天数阀值，LogStore热存储天数配置大于该天数且未配置智能冷热分层存储则视为风险项
sls_hot_ttl_threshold_days = 60


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


class StorageResourceSample:
    def __init__(self):
        pass

    @staticmethod
    def create_oss_client(
            account_id: str,
            access_key_id: str,
            access_key_secret: str,
            role_name: str,
            region_id: str,
    ) -> Oss20190517Client:
        config = OpenAPI.create_api_models_config_by_assume_role(account_id,
                                                                 access_key_id,
                                                                 access_key_secret,
                                                                 role_name)

        config.endpoint = f'oss-{region_id}.aliyuncs.com'
        return Oss20190517Client(config)

    @staticmethod
    def query_oss_bucket_list(client: Oss20190517Client):
        list_project_request = oss_20190517_models.ListBucketsRequest()
        try:
            resp = client.list_buckets(list_project_request)
            resp_map = resp.body.to_map()
            # print(resp_map)
            if 'Buckets' in resp_map:
                return resp_map['Buckets']['Bucket']
            else:
                return []

        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_oss_risk_item(account_id,
                            access_key_id,
                            access_key_secret,
                            role_name):

        # for region_id in region_id_list:
        client = StorageResourceSample.create_oss_client(account_id,
                                                         access_key_id,
                                                         access_key_secret,
                                                         role_name,
                                                         'cn-shanghai')

        buckets = StorageResourceSample.query_oss_bucket_list(client)

        for bucket in buckets:
            bucket_region = bucket['Region']
            bucket_name = bucket['Name']
            client = StorageResourceSample.create_oss_client(account_id,
                                                             access_key_id,
                                                             access_key_secret,
                                                             role_name,
                                                             bucket_region)

            output_str = f'AccountId: {account_id} Region:{bucket_region}\n'
            try:
                resp = client.get_bucket_lifecycle(bucket_name)
                # print(resp.to_map()['body'])
                rules = resp.to_map()['body']['Rule']
                if rules is not list:
                    rules = [rules]
                exist_enable_rule = False
                for rule in rules:
                    if rule['Status'] == 'Enable':
                        exist_enable_rule = True
                        break
                if not exist_enable_rule:
                    output_str += f'<Bucket:{bucket_name} Info:无启用的生命周期规则>\n'

            except TeaException as error:
                if error.code == 'NoSuchLifecycle':
                    output_str += f'<Bucket:{bucket_name} Info:无生命周期规则>\n'
                # print(error)
            except Exception as error:
                print(repr(error))
            print(output_str)

    @staticmethod
    def create_sls_client(
            account_id: str,
            access_key_id: str,
            access_key_secret: str,
            role_name: str,
            region_id: str,
    ) -> Sls20201230Client:
        config = OpenAPI.create_api_models_config_by_assume_role(account_id,
                                                                 access_key_id,
                                                                 access_key_secret,
                                                                 role_name)

        config.endpoint = f'{region_id}.log.aliyuncs.com'
        return Sls20201230Client(config)

    @staticmethod
    def query_sls_project(client: Sls20201230Client):
        list_project_request = sls_20201230_models.ListProjectRequest(size=500)
        runtime = util_models.RuntimeOptions()
        headers = {}
        try:
            resp = client.list_project_with_options(list_project_request, headers, runtime)
            return resp.body.to_map()['projects']
        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_sls_store(client: Sls20201230Client, project_name: str):
        list_log_stores_request = sls_20201230_models.ListLogStoresRequest()
        runtime = util_models.RuntimeOptions()
        headers = {}
        try:
            resp = client.list_log_stores_with_options(project_name, list_log_stores_request, headers, runtime)
            log_store_names = resp.body.to_map()['logstores']
            log_store_detail_list = []
            for log_store_name in log_store_names:
                resp = client.get_log_store_with_options(project_name, log_store_name, headers, runtime)
                log_store_detail_list.append(resp.body.to_map())
            # print(log_store_detail_list)
            return log_store_detail_list
        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_sls_index(client: Sls20201230Client, project_name: str, log_store_name: str, ):
        runtime = util_models.RuntimeOptions()
        headers = {}
        try:
            resp = client.get_index_with_options(project_name, log_store_name, headers, runtime)
            return resp.body.to_map()
        except Exception as error:
            UtilClient.assert_as_string(error)

    @staticmethod
    def query_sls_risk_item(account_id,
                            access_key_id,
                            access_key_secret,
                            role_name):

        permanent_storage_days = 3650
        for region_id in region_id_list:
            output_str = f'AccountId: {account_id} Region:{region_id}\n'
            client = StorageResourceSample.create_sls_client(account_id, access_key_id, access_key_secret, role_name,
                                                             region_id)

            projects = StorageResourceSample.query_sls_project(client)
            for project in projects:
                project_name = project['projectName']

                if project['status'] != 'Normal':
                    continue

                log_store_list = StorageResourceSample.query_sls_store(client, project_name)
                for log_store in log_store_list:
                    log_store_name = log_store["logstoreName"]
                    if log_store['ttl'] >= permanent_storage_days:
                        output_str += f'<Project:{project_name} LogStore:{log_store_name} Risk:开启永久存储>\n'
                        continue

                    if log_store['ttl'] >= sls_ttl_threshold_days:
                        output_str += f'<Project:{project_name} LogStore:{log_store_name} ' \
                                      f'Warn:存储天数大于设定的{sls_ttl_threshold_days}天阀值>\n'

                    if log_store['ttl'] > sls_hot_ttl_threshold_days and 'hot_ttl' not in log_store:
                        output_str += f'<Project:{project_name} LogStore:{log_store_name} ' \
                                      f'Warn:热存储超过{sls_hot_ttl_threshold_days}天，未开启冷存储>\n'

                    index = StorageResourceSample.query_sls_index(client, project_name, log_store_name)
                    if 'line' in index:
                        output_str += f'<Project:{project_name} LogStore:{log_store_name} Warn:开启全文索引\n'
                    if 'log_reduce' in index and index['log_reduce']:
                        output_str += f'<Project:{project_name} LogStore:{log_store_name} Info:开启日志聚类\n'

            print(output_str)

    @staticmethod
    def query_risk_item():
        rd_member_account_rd_account_access_role = 'ResourceDirectoryAccountAccessRole'

        print(f'[OSS Risk Item]')
        try:
            StorageResourceSample.query_oss_risk_item(rd_management_account_id,
                                                      rd_management_account_access_key_id,
                                                      rd_management_account_access_key_secret,
                                                      rd_management_account_readonly_role_name)
        except Exception as error:
            print(f'AccountId {rd_management_account_id} query exception:{repr(error)}')

        for account_id in rd_member_account_id_list:
            try:
                StorageResourceSample.query_oss_risk_item(account_id,
                                                          rd_management_account_access_key_id,
                                                          rd_management_account_access_key_secret,
                                                          rd_member_account_rd_account_access_role)
            except Exception as error:
                # traceback.print_exc()
                print(f'AccountId {account_id} query exception:{repr(error)}')


        # SLS
        print(f'[SLS Risk Item]')
        try:
            StorageResourceSample.query_sls_risk_item(rd_management_account_id,
                                                      rd_management_account_access_key_id,
                                                      rd_management_account_access_key_secret,
                                                      rd_management_account_readonly_role_name)
        except Exception as error:
            # traceback.print_exc()
            print(f'AccountId {rd_management_account_id} query exception:{repr(error)}')

        for account_id in rd_member_account_id_list:
            try:
                StorageResourceSample.query_sls_risk_item(account_id,
                                                          rd_management_account_access_key_id,
                                                          rd_management_account_access_key_secret,
                                                          rd_member_account_rd_account_access_role)
            except Exception as error:
                # traceback.print_exc()
                print(f'AccountId {account_id} query exception:{repr(error)}')


if __name__ == '__main__':
    StorageResourceSample.query_risk_item()
