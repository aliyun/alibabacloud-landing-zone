# -*- coding:utf-8 -*-
import logging
import json
from typing import Dict, List
import random
from aliyunsdkcore.client import AcsClient
from aliyunsdkcore.request import CommonRequest
from aliyunsdkcore.auth.credentials import StsTokenCredential, AccessKeyCredential
from aliyunsdksts.request.v20150401.AssumeRoleRequest import AssumeRoleRequest
from aliyunsdkresourcemanager.request.v20200331.ListAccountsRequest import ListAccountsRequest
from aliyunsdkresourcemanager.request.v20200331.CreateServiceLinkedRoleRequest import CreateServiceLinkedRoleRequest
from alibabacloud_fc_open20210406.client import Client as FC_Open20210406Client
from alibabacloud_tea_openapi import models as open_api_models
from alibabacloud_fc_open20210406 import models as fc__open_20210406_models
from alibabacloud_tea_util import models as util_models
from alibabacloud_eventbridge.client import Client as EventBridgeClient
from alibabacloud_eventbridge import models as event_bridge_models
from alibabacloud_tea_console.client import Client as ConsoleClient
from alibabacloud_tea_util.client import Client as UtilClient
from aliyunsdkram.request.v20150501.AttachPolicyToRoleRequest import AttachPolicyToRoleRequest
from aliyunsdkram.request.v20150501.CreateRoleRequest import CreateRoleRequest
from Tea.model import TeaModel

from config_default import master_account_secret_key, master_account_access_key, region_id, event_bridge_bus_name, \
    event_bridge_rule_name, fc_endpoint
from config_default import member_account_eventbridge_filter, log_account_put_events_policy
from config_default import log_archive_uid,member_uid,member_uid_role_name,sec_group_id,vswitch,vpc
from config_default import fc_role,srv_name,fc_name,code_oss_bucket_name,code_oss_object_name,mysql_endpoint
from config_default import mysql_port,mysql_user,mysql_password,mysql_dbname


class RAM(object):

    def __init__(self, sts_access_key, sts_access_secret, sts_token, region_id):
        self.sts_access_key = sts_access_key
        self.sts_access_secret = sts_access_secret
        self.sts_token = sts_token
        self.region_id = region_id

        self.credentials = StsTokenCredential(self.sts_access_key, self.sts_access_secret, self.sts_token)
        self.clt = AcsClient(region_id=self.region_id, credential=self.credentials)

    def ListUsers(self):
        '''查询全部RAM用户列表
        '''
        request = CommonRequest()
        request.set_accept_format('json')
        request.set_domain('ram.aliyuncs.com')
        request.set_method('POST')
        request.set_protocol_type('https')  # https | http
        request.set_version('2015-05-01')
        request.set_action_name('ListUsers')

        response = self.clt.do_action(request)
        logging.debug(str(response, encoding='utf-8'))
        return json.loads(response)

    # 创建自定义角色.
    """
    @params: service 指的是成员账号的UID，需要在日志账号中配置权限策略
    """
    def CreateRole(self, roleName, desc, service):
        request = CreateRoleRequest()
        request.set_RoleName(roleName)
        policy = f'{{\"Statement\": [{{\"Action\": \"sts:AssumeRole\",\"Effect\": \"Allow\",\"Principal\": {{\"Service\": [\"{service}@eventbridge.aliyuncs.com\"]}}}}],\"Version\": \"1\"}}'
        request.set_AssumeRolePolicyDocument(policy)
        request.set_Description(desc)
        request.set_accept_format('json')
        try:
            response = self.clt.do_action_with_exception(request)
            ConsoleClient.log((str(response, encoding='utf-8')))
        except Exception as error:
            ConsoleClient.log(error.message)

    # 给指定的角色赋权限
    def AttachPolicyToRole(self, policyName, roleName, policyType):
        request = AttachPolicyToRoleRequest()
        request.set_accept_format('json')
        request.set_PolicyType(policyType)
        request.set_PolicyName(policyName)
        request.set_RoleName(roleName)
        try:
            response = self.clt.do_action_with_exception(request)
            ConsoleClient.log(str(response, encoding='utf-8'))
        except Exception as error:
            ConsoleClient.log(error.message)

    # 创建服务关联角色
    '''
    @Link 支持服务关联角色的列表 https://help.aliyun.com/document_detail/160674.htm
    '''
    def CreateServiceLinkedRole(self, serviceName):
        request = CreateServiceLinkedRoleRequest()
        request.set_ServiceName(serviceName)
        request.set_accept_format('json')
        try:
            response = self.clt.do_action_with_exception(request)

            ConsoleClient.log(str(response, encoding='utf-8'))
        except Exception as error:
            ConsoleClient.log(error.message)


class ResourceManage(object):
    def __init__(self, sts_access_key, sts_access_secret, region_id):
        self.sts_access_key = sts_access_key
        self.sts_access_secret = sts_access_secret
        self.region_id = region_id
        self.credentials = AccessKeyCredential(self.sts_access_key, self.sts_access_secret)

        self.clt = AcsClient(region_id=self.region_id, credential=self.credentials)

    def ListAccounts(self):
        '''
        获取当前资源目录下的全部账号列表
        Returns:
        '''
        request = ListAccountsRequest()
        request.set_accept_format('json')

        response = self.clt.do_action_with_exception(request)
        member_ids = []
        for account in json.loads(response).get("Accounts").get("Account"):
            member_ids.append(account.get("AccountId"))
        return member_ids

    def AssumeRole(self, memberUid):
        request = AssumeRoleRequest()
        request.set_accept_format('json')
        request.set_RoleArn("acs:ram::%s:role/resourcedirectoryaccountaccessrole" % (memberUid))
        request.set_RoleSessionName("rdMaster")
        response = self.clt.do_action_with_exception(request)

        response = json.loads(response).get('Credentials')
        return response.get('AccessKeyId'), response.get('AccessKeySecret'), response.get('SecurityToken')


class EventBridge(object):
    def __init__(self):
        pass

    """
    @params account: 当前成员账号的UID
    """

    @staticmethod
    def createClient(sts_access_key, sts_access_secret, sts_token, account):
        global region_id
        """
        Create client初始化公共请求参数。
        """
        config = event_bridge_models.Config()
        # 您的AccessKey ID。
        config.access_key_id = sts_access_key
        # 您的AccessKey Secret。
        config.access_key_secret = sts_access_secret
        # STS
        config.security_token = sts_token

        # 您的接入点。
        config.endpoint = account + ".eventbridge." + region_id + ".aliyuncs.com"
        return EventBridgeClient(config)

    """
    @param logArchiveUid: 日志账号的UID
    在成员账号中配置事件目标为日志账号
    """

    @staticmethod
    def createEventRule(client, logArchiveUid, logArchiveRole, ruleName):
        global region_id, event_bridge_bus_name, member_account_eventbridge_filter
        try:
            create_event_rule_request = event_bridge_models.CreateRuleRequest()

            target_entry = event_bridge_models.TargetEntry()
            target_entry.id = random.randint(0,10000)
            target_entry.type = "acs.eventbridge"
            target_entry.endpoint = "acs:eventbridge:" + region_id + ":" + logArchiveUid + ":eventbus/default"
            target_entry.push_retry_strategy = "BACKOFF_RETRY"
            # 需要使用指定的Model初始化对象
            param_list = []
            paramObject1 = event_bridge_models.EBTargetParam("AccountType", "CONSTANT", "AnotherAccount", None)
            paramObject2 = event_bridge_models.EBTargetParam("AccountId", "CONSTANT", logArchiveUid, None)
            paramObject3 = event_bridge_models.EBTargetParam("EventBusName", "CONSTANT", event_bridge_bus_name, None)
            paramObject4 = event_bridge_models.EBTargetParam("RAMRoleName", "CONSTANT", logArchiveRole, None)
            paramObject5 = event_bridge_models.EBTargetParam("Body", "ORIGINAL", "", None)
            param_list.append(paramObject1)
            param_list.append(paramObject2)
            param_list.append(paramObject3)
            param_list.append(paramObject4)
            param_list.append(paramObject5)

            target_entry.param_list = param_list
            # 定义Targets 事件列表
            target_entry_list = [
                target_entry
            ]
            create_event_rule_request.rule_name = ruleName
            create_event_rule_request.event_bus_name = event_bridge_bus_name
            # TODO 用户需要参考内部规范，配置对应的过滤条件
            create_event_rule_request.filter_pattern = member_account_eventbridge_filter
            create_event_rule_request.status = "enable"
            create_event_rule_request.targets = target_entry_list

            response = client.create_rule(create_event_rule_request)
            ConsoleClient.log("--------------------create rule success--------------------")
            ConsoleClient.log(UtilClient.to_jsonstring(response.to_map()))
        except Exception as error:
            ConsoleClient.log(error.message)




class FCConfig(TeaModel):
    def __init__(
            self,
            access_key: str = None,
            sk: str = None,
            sts: str = None,
            account: str = None,
            sec_group_id: str = None,
            vswitch: List[str] = None,
            vpc: str = None,
            fc_role: str = None,
            srv_name: str = None,
            code_oss_bucket_name: str = None,
            code_oss_object_name: str = None,
            fc_name: str = None,
            mysql_endpoint: str = None,
            mysql_port: str = None,
            mysql_user: str = None,
            mysql_password: str = None,
            mysql_dbname: str = None,
    ):
        # ak / sk / sts
        self.ak = access_key
        self.sk = sk
        self.sts = sts
        # 日志账号的UID，用于配置函数计算
        self.account = account
        # 网络配置
        self.sec_group_id = sec_group_id
        self.vswitch = vswitch
        self.vpc = vpc

        # 角色 / 名称
        self.fc_role = fc_role
        self.srv_name = srv_name
        self.fc_name = fc_name

        # OSS配置相关用于存储FC函数
        self.code_oss_bucket_name = code_oss_bucket_name
        self.code_oss_object_name = code_oss_object_name

        # MYSQL相关连接
        self.mysql_endpoint = mysql_endpoint
        self.mysql_port = mysql_port
        self.mysql_user = mysql_user
        self.mysql_password = mysql_password
        self.mysql_dbname = mysql_dbname


# 定义函数计算配置
class Fc(object):
    def __init__(self):
        pass

    """
    @params account: 日志账号的UID
    """

    @staticmethod
    def createClient(
            access_key_id: str,
            access_key_secret: str,
            security_token: str,
            account: str, ) -> FC_Open20210406Client:
        global region_id
        config = open_api_models.Config(
            # 您的AccessKey ID
            access_key_id=access_key_id,
            # 您的AccessKey Secret
            access_key_secret=access_key_secret,
            # security_token
            security_token=security_token
        )
        # 访问的域名
        config.endpoint = account + "." + region_id + ".fc.aliyuncs.com"
        return FC_Open20210406Client(config)

    @staticmethod
    def createService(args: FCConfig, ) -> None:
        client = Fc.createClient(args.ak, args.sk, args.sts, args.account)

        create_service_headers = fc__open_20210406_models.CreateServiceHeaders()
        vpcconfig = fc__open_20210406_models.VPCConfig(
            security_group_id=args.sec_group_id,
            v_switch_ids=args.vswitch,
            vpc_id=args.vpc
        )
        create_service_request = fc__open_20210406_models.CreateServiceRequest(
            internet_access=True,
            role=args.fc_role,
            service_name=args.srv_name,
            vpc_config=vpcconfig
        )
        runtime = util_models.RuntimeOptions()
        try:
            # 复制代码运行请自行打印 API 的返回值
            client.create_service_with_options(create_service_request, create_service_headers, runtime)
            ConsoleClient.log("创建服务{}成功".format(args.srv_name))
        except Exception as error:
            # 如有需要，请打印 error
            ConsoleClient.log(error.message)

    @staticmethod
    def createFunction(args: FCConfig, ) -> None:
        client = Fc.createClient(args.ak, args.sk, args.sts, args.account)
        create_function_headers = fc__open_20210406_models.CreateFunctionHeaders()

        lifecycConfig = fc__open_20210406_models.InstanceLifecycleConfig(
            pre_stop=fc__open_20210406_models.LifecycleHook(
                handler="index.pre_stop",
            ),
        )
        code = fc__open_20210406_models.Code(
            oss_bucket_name=args.code_oss_bucket_name,
            oss_object_name=args.code_oss_object_name
        )

        create_function_request = fc__open_20210406_models.CreateFunctionRequest(
            function_name=args.fc_name,
            code=code,
            environment_variables={"MYSQL_ENDPOING": args.mysql_endpoint, "MYSQL_USER": args.mysql_user,"MYSQL_PORT": args.mysql_port, "MYSQL_PASSWORD": args.mysql_password,"MYSQL_DBNAME": args.mysql_dbname},
            handler="index.handler",
            initializer="index.initializer",
            runtime="python3",
            instance_lifecycle_config=lifecycConfig
        )



        print(create_function_request)
        print(args.srv_name)
        print(args.fc_name)

        runtime = util_models.RuntimeOptions()
        try:
            # 复制代码运行请自行打印 API 的返回值
            client.create_function_with_options(args.srv_name, create_function_request, create_function_headers,
                                                runtime)
            ConsoleClient.log("创建函数:{}成功".format(args.fc_name))
        except Exception as error:
            # 如有需要，请打印 error
            ConsoleClient.log(error.message)

def pipeline():
    global event_bridge_rule_name, log_account_put_events_policy,region_id
    global log_archive_uid,member_uid,member_uid_role_name
    global sec_group_id,vswitch,vpc,fc_role,srv_name
    global fc_name,code_oss_bucket_name,code_oss_object_name
    global mysql_endpoint,mysql_port,mysql_dbname,mysql_password,mysql_user

    rdMaster = ResourceManage(master_account_access_key, master_account_secret_key, region_id)

    # 1、先在日志账号添加角色，信任策略为成员账号 [参数：日志账号]
    (sts_ak, sts_sk, sts_token) = rdMaster.AssumeRole(log_archive_uid)
    ram = RAM(sts_ak, sts_sk, sts_token, region_id)
    ConsoleClient.log("【第一步】日志账号中配置事件总线跨账号路由所需要的角色")
    ram.CreateRole(member_uid_role_name, "账号投递的角色", member_uid)
    ram.AttachPolicyToRole(log_account_put_events_policy, member_uid_role_name, "System")

    # 2、在日志账号中完成函数计算配置 - 配置服务
    args = FCConfig(
        access_key=sts_ak,
        sk=sts_sk,
        sts=sts_token,
        account=log_archive_uid,
        sec_group_id=sec_group_id,
        vswitch=vswitch,
        vpc=vpc,
        fc_role=fc_role,
        srv_name=srv_name,
        fc_name=fc_name,
        code_oss_bucket_name=code_oss_bucket_name,
        code_oss_object_name=code_oss_object_name,
        mysql_endpoint=mysql_endpoint,
        mysql_port=mysql_port,
        mysql_user=mysql_user,
        mysql_password=mysql_password,
        mysql_dbname=mysql_dbname,
    )
    ConsoleClient.log("【第二步】日志账号中配置函数计算服务")
    # Fc.createService(args)

    # 3、在日志账号中完成函数计算配置- 配置函数
    ConsoleClient.log("【第三步】日志账号中配置函数计算服务,定义函数配置")
    Fc.createFunction(args)

    # 4、对成员账号配置SLR [参数：成员账号的UID]
    (sts_ak, sts_sk, sts_token) = rdMaster.AssumeRole(member_uid)
    ram = RAM(sts_ak, sts_sk, sts_token, region_id)
    ConsoleClient.log("【第四步】成员账号中配置事件总线所需要用到的SLR")
    ram.CreateServiceLinkedRole("source-actiontrail.eventbridge.aliyuncs.com")

    # 5、对成员账号配置事件总线
    ConsoleClient.log("【第五步】成员账号中配置事件总线：配置规则过滤及事件投递目标")
    client = EventBridge.createClient(sts_ak, sts_sk, sts_token, member_uid)
    EventBridge.createEventRule(client, log_archive_uid, member_uid_role_name, event_bridge_rule_name)




if __name__ == '__main__':
    pipeline()
