#!/bin/bash
SystemAdminAccessPolicyName=SystemAdministratorAccess
SystemAdminGroupName=SystemAdminGroup
BillingAdminGroupName=BillingAdminGroup
CommonUserGroupName=CommonUserGroup
# 为系统管理员创建自定义权限策略
cat <<EOF > ./system_admin_access_policy.txt
{
    "Statement": [
        {
            "Effect": "Allow",
            "NotAction":
                [
                    "ram:*",
                    "ims:*",
                    "resourcemanager:*",
                    "bss:*",
                    "bssapi:*",
                    "efc:*"
                ],
            "Resource": "*"
        },
        {
            "Effect": "Allow",
            "Action":
                [
                    "ram:GetRole",
                    "ram:ListRoles",
                    "ram:CreateServiceLinkedRole",
                    "ram:DeleteServiceLinkedRole",
                    "bss:DescribeOrderList",
                    "bss:DescribeOrderDetail",
                    "bss:PayOrder",
                    "bss:CancelOrder"
                ],
            "Resource": "*"
        }
    ],
    "Version": "1"
}
EOF
aliyun ram CreatePolicy --PolicyName $SystemAdminAccessPolicyName --Description "系统管理员权限" --PolicyDocument "$(cat ./system_admin_access_policy.txt)"
# 设置RAM用户密码强度
aliyun ram SetPasswordPolicy --MinimumPasswordLength 8 --RequireLowercaseCharacters true --RequireUppercaseCharacters true --RequireNumbers true --RequireSymbols true --HardExpiry false --MaxPasswordAge 90 --PasswordReusePrevention 8 --MaxLoginAttemps 5
# 创建云管理员组
aliyun ram CreateGroup --GroupName $CloudAdminGroupName --Comments 云管理员组
# 为云管理员组授权
aliyun ram AttachPolicyToGroup --PolicyName "AdministratorAccess" --PolicyType "System" --GroupName $CloudAdminGroupName
# 创建系统管理员组
aliyun ram CreateGroup --GroupName $SystemAdminGroupName --Comments 系统管理员组
# 为系统管理员组授权
aliyun ram AttachPolicyToGroup --PolicyName $SystemAdminAccessPolicyName --PolicyType "Custom" --GroupName $SystemAdminGroupName
# 创建财务账单管理员组
aliyun ram CreateGroup --GroupName $BillingAdminGroupName --Comments 财务账单管理员组
# 为财务账单管理员组授权AliyunBSSFullAccess
aliyun ram AttachPolicyToGroup --PolicyName "AliyunBSSFullAccess" --PolicyType "System" --GroupName $BillingAdminGroupName
# 为财务账单管理员组授权AliyunFinanceConsoleFullAccess
aliyun ram AttachPolicyToGroup --PolicyName "AliyunFinanceConsoleFullAccess" --PolicyType "System" --GroupName $BillingAdminGroupName
# 创建普通用户组
aliyun ram CreateGroup --GroupName $CommonUserGroupName --Comments 普通用户组
