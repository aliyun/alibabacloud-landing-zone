#!/usr/bin/env python
#coding=utf-8

from aliyunsdksts.request.v20150401.AssumeRoleWithOIDCRequest import AssumeRoleWithOIDCRequest
import json
import logging
import sys
import time
from aliyunsdkcore.client import AcsClient
from aliyunsdkcore.request import CommonRequest
from aliyunsdkcore.auth.credentials import StsTokenCredential,AccessKeyCredential


class RAM(object):

    def __init__(self, sts_access_key, sts_access_secret, sts_token, region_id):
        self.sts_access_key = sts_access_key
        self.sts_access_secret = sts_access_secret
        self.sts_token = sts_token
        self.region_id = region_id
        # 使用sts
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


class OIDC(object):
    def __init__(self, access_key, access_secret, region_id):
        self.access_key = access_key
        self.access_secret = access_secret
        self.region_id = region_id
        # 使用oidc
        self.credentials = AccessKeyCredential(self.access_key, self.access_secret)
        self.clt = AcsClient(region_id=self.region_id, credential=self.credentials)

    def get_sts_credentials(self,oidc_provider_arn,role_arn,oidc_token,role_session_name):
        request = AssumeRoleWithOIDCRequest()
        request.set_accept_format('json')

        request.set_OIDCProviderArn(oidc_provider_arn)
        request.set_RoleArn(role_arn)
        request.set_OIDCToken(oidc_token)
        request.set_RoleSessionName(role_session_name)

        response = self.clt.do_action_with_exception(request)
        return json.loads(response)


def read_oidc_token(file_path):
    with open(file_path,'r') as f:
        ff=f.read()
    return ff


if __name__ == '__main__':
    # 先通过OIDC拿到STS
    STS_ASSUME_ROLE_AK = "xx"
    STS_ASSUME_ROLE_SK = "xxx"
    REGION_ID = "cn-shanghai"
    OIDCProviderArn = "acs:ram::1146716667364xxx:oidc-provider/ack-rrsa-c1f8defef6e4b41dc81a6a20235eb8631"
    RoleArn = "acs:ram::1146716667364xxx:role/ack-app-sts-role"
    RoleSessionName = "yaofangapp"

    OIDCToken = read_oidc_token("/var/run/secrets/tokens/oidc-token")

    oidc = OIDC(STS_ASSUME_ROLE_AK,STS_ASSUME_ROLE_SK,REGION_ID)
    try:
        sts_tuple = oidc.get_sts_credentials(OIDCProviderArn,RoleArn,OIDCToken,RoleSessionName)
    except Exception as ex:
        logging.error("OIDC Token失效，导致查询STS失效,程序上面做次重试")
        time.sleep(1000)
        sts_tuple = oidc.get_sts_credentials(OIDCProviderArn, RoleArn, OIDCToken, RoleSessionName)

    try:
        sts_ak,sts_sk,sts_token = sts_tuple["Credentials"]["AccessKeyId"],sts_tuple["Credentials"]["AccessKeySecret"],sts_tuple["Credentials"]["SecurityToken"]
        ram = RAM(sts_ak,sts_sk,sts_token,REGION_ID)
        r = ram.ListUsers()
        print(r)
    except Exception as ex:
        sys.exit()
