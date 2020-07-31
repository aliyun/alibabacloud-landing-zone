#!/bin/bash

Region="cn-hangzhou"
Zone="cn-hangzhou-h"
# 创建企业默认专有网络（VPC）
VpcId=$(aliyun vpc CreateVpc --region $Region --VpcName "default_vpc" --CidrBlock "192.168.0.0/16" | jq -r '.VpcId')
# 等待VPC可用
aliyun vpc DescribeVpcs --region $Region --VpcId $VpcId --waiter expr="Vpcs.Vpc[0].Status" to="Available"
# 创建交换机
VswId=$(aliyun vpc CreateVSwitch --region $Region --CidrBlock "192.168.0.0/24" --VpcId $VpcId --ZoneId $Zone --VSwitchName "default_vswitch" | jq -r '.VSwitchId')
# 创建企业默认安全组
SgId=$(aliyun ecs CreateSecurityGroup --region $Region --SecurityGroupName "default_sg" --VpcId $VpcId | jq -r '.SecurityGroupId')