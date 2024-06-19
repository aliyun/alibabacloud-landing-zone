import os
from aliyunsdkcore.client import AcsClient
from aliyunsdkcore.acs_exception.exceptions import ClientException
from aliyunsdkcore.acs_exception.exceptions import ServerException
from aliyunsdkcore.auth.credentials import EcsRamRoleCredential
from aliyunsdkvpc.request.v20160428.DescribeVpcsRequest import DescribeVpcsRequest

cred = EcsRamRoleCredential(
    role_name='my-ecs-role'
)

client = AcsClient(
    region_id='cn-hangzhou',
    credential=cred
)

request = DescribeVpcsRequest()
request.set_accept_format('json')

response = client.do_action_with_exception(request)
# python2:  print(response) 
print(str(response, encoding='utf-8'))
