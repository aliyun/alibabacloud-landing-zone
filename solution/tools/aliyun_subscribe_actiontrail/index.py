# -*- coding:utf-8 -*-
from aliyunsdkcore.client import AcsClient
from aliyunsdkcore.auth.credentials import StsTokenCredential, AccessKeyCredential
from aliyunsdkecs.request.v20140526.DescribeSnapshotsRequest import DescribeSnapshotsRequest
from aliyunsdkresourcemanager.request.v20200331.MoveResourcesRequest import MoveResourcesRequest
import pymysql
from aliyunsdksts.request.v20150401.AssumeRoleRequest import AssumeRoleRequest
import json, logging
import os

connection = None
logger = logging.getLogger()

class ECS(object):

    def __init__(self, sts_access_key, sts_access_secret, sts_token, region_id):
        self.sts_access_key = sts_access_key
        self.sts_access_secret = sts_access_secret
        self.sts_token = sts_token
        self.region_id = region_id
        if sts_token is None:
            self.credentials = AccessKeyCredential(self.sts_access_key, self.sts_access_secret)
        else:
            self.credentials = StsTokenCredential(self.sts_access_key, self.sts_access_secret, self.sts_token)
        self.clt = AcsClient(region_id=self.region_id, credential=self.credentials)

    def ListDiskSnapshot(self, diskid):
        request = DescribeSnapshotsRequest()
        request.set_accept_format('json')
        request.set_DiskId(diskid)
        request.set_PageSize(100)
        response = self.clt.do_action_with_exception(request)
        return response

    def ListEcsDiskSnapshot(self, instanceid):
        request = DescribeSnapshotsRequest()
        request.set_accept_format('json')
        request.set_InstanceId(instanceid)
        request.set_PageSize(100)
        response = self.clt.do_action_with_exception(request)
        return response

    def MoveResources(self, target_rg, resources):
        request = MoveResourcesRequest()
        request.set_accept_format('json')

        request.set_ResourceGroupId(target_rg)
        request.set_Resources(resources)
        response = self.clt.do_action_with_exception(request)
        logger.info("批量转组成功,转组对象:" + json.dumps(resources))

class ResourceManage(object):
    def __init__(self, sts_access_key, sts_access_secret, region_id):
        self.sts_access_key = sts_access_key
        self.sts_access_secret = sts_access_secret
        self.region_id = region_id
        self.credentials = AccessKeyCredential(self.sts_access_key, self.sts_access_secret)
        self.clt = AcsClient(region_id=self.region_id, credential=self.credentials)

    """
    当需要通过资源管理账号操作多个成员账号时适用
    """

    def AssumeRole(self, memberUid):
        request = AssumeRoleRequest()
        request.set_accept_format('json')
        request.set_RoleArn("acs:ram::%s:role/resourcedirectoryaccountaccessrole" % (memberUid))
        request.set_RoleSessionName("rdMaster")
        response = self.clt.do_action_with_exception(request)

        response = json.loads(response).get('Credentials')
        return response.get('AccessKeyId'), response.get('AccessKeySecret'), response.get('SecurityToken')


def initialize(context):
    global connection
    try:
        connection = pymysql.connect(
            host=os.environ['MYSQL_ENDPOING'],  # 替换为您的HOST名称。
            port=int(os.environ['MYSQL_PORT']),  # 替换为您的端口号。
            user=os.environ['MYSQL_USER'],  # 替换为您的用户名。
            passwd=os.environ['MYSQL_PASSWORD'],  # 替换为您的用户名对应的密码。
            db=os.environ['MYSQL_DBNAME'],  # 替换为您的数据库名称。
            connect_timeout=5)
        logger.info('eb job connect mysql success!!!')
    except Exception as e:
        logger.error(
            "ERROR: Unexpected error: Could not connect to MySql instance.")


def pre_stop(context):
    if connection != None:
        connection.close()


"""
通过主键异常来发现是否当前这条EB事件有处理过
"""
def save_transactional(sql, params):
    if connection is None:
        return True
    try:
        cursor = connection.cursor()
        cursor.execute(sql, params)
        connection.commit()
        return True
    except Exception as e:
        logger.error(e)
    return False


def check_pk(sql):
    if connection is None:
        # 表示不做幂等验证,直接处理
        return None
    try:
        cursor = connection.cursor()
        cursor.execute(sql)
        data = cursor.fetchone()
        return data
    except Exception as e:
        logger.error(e)


def scene(context):
    """
    Params: context 格式
    {
        "accountId":"",
        "eventId:":"",
        "rgId":"",
        "resId":"",
        "resType":"disk"
    }
    场景一：有两个账号：
    1、日志账号，开通事件总线 + 函数计算 + RDS，用于响应事件。
    2、业务账号，开通事件总线，将事件总线消息推到日志账号。

    场景二：有三个账号：
    1、日志账号，开通事件总线 + 函数计算 + RDS，用于响应事件。
    2、资源管理账号，开通资源目录，在资源目录里面统一管理成员账号变更资源组。
    3、业务账号，开通事件总线，将事件总线消息推到日志账号。
    """
    if os.environ.get("AK") is None or os.environ.get("SK") is None or os.environ.get(
            "REGION") is None or os.environ.get("SCENE") is None:
        logger.error("没有配置程序用的AK/SK/Region，请检查环境变量")
        return
    else:
        ak = os.environ.get("AK")
        sk = os.environ.get("SK")

    region_id = os.environ.get("REGION")
    # 1. 先判断幂等
    pk_sql = "insert into pk_eventbridge(eb_id) values(%s)"
    params = (context.get("eventId"))
    if not save_transactional(pk_sql, params):
        logger.error("当前事件%s已处理过，忽略" % (context.get("eventId")))
        return

    # 2. 依据磁盘ID或实例ID查询快照列表
    resId = context.get("resId")

    # 需要判断一下是场景一还是场景二
    scene_type = os.environ.get("SCENE")
    if scene_type is not None and scene_type == "ma":
        # 需要先在MA账号里面AssumeRole到成员账号拿到STS_TOKEN
        resM = ResourceManage(ak, sk, region_id)
        sts_ak, sts_sk, sts_token = resM.AssumeRole(context.get("accountId"))
        ecs = ECS(sts_ak, sts_sk, sts_token, region_id)
    else:
        ecs = ECS(ak, sk, None, region_id)

    # 做多一步判断，如果是disk直接就查快照，如果是ECS实例则需要先查询出磁盘出来
    if context.get("resType") == "instance":
        resp = ecs.ListEcsDiskSnapshot(resId)
    if context.get("resType") == "disk":
        resp = ecs.ListDiskSnapshot(resId)
    if resp is None:
        return
    snap_object = json.loads(resp)
    if len(snap_object["Snapshots"]["Snapshot"]) == 0:
        logger.error("当前账号:%s,磁盘没有快照%s不需要处理，忽略" % (context.get("accountId"), context.get("resId")))
        return

    resources = []
    for item in snap_object["Snapshots"]["Snapshot"]:
        resources.append(
            {"ResourceId": item["SnapshotId"], "RegionId": region_id, "Service": "ecs", "ResourceType": "snapshot"})

    # 3. 批量转组
    ecs.MoveResources(context.get("rgId"), resources)


def transfer(event):
    eb = json.loads(event).get("data")
    context = {
        "accountId": eb.get("recipientAccountId"),
        "eventId": eb.get("eventId"),
        "rgId": eb.get("requestParameters").get("ResourceGroupId"),
        "resId": eb.get("requestParameters").get("ResourceId"),
        "resType": eb.get("requestParameters").get("ResourceType")
    }
    if context.get("resType") == "disk" or context.get("resType") == "instance":
        return context
    return None


def handler(event, context):
    transfer_context = transfer(event)
    if transfer_context is None:
        return
    scene(transfer_context)
