# (企业可定制修改)application账号要创建所在的文件夹ID
# folder_id = "fd-Zn1wkx2Dws"

# 配置用于联合登录的 SAML App 名字(企业可定制修改)
sso_provider_name = "idp"

# 企业定制修改
encodedsaml_metadata_document = "PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiPz48bWQ6RW50aXR5RGVzY3JpcHRvciB4bWxuczptZD0idXJuOm9hc2lzOm5hbWVzOnRjOlNBTUw6Mi4wOm1ldGFkYXRhIiBlbnRpdHlJRD0iaHR0cHM6Ly9jaGVuZ2NoYW8ubmFtZS9iNjVkNzZjZTQyNjAvIj48bWQ6SURQU1NPRGVzY3JpcHRvciBXYW50QXV0aG5SZXF1ZXN0c1NpZ25lZD0iZmFsc2UiIHByb3RvY29sU3VwcG9ydEVudW1lcmF0aW9uPSJ1cm46b2FzaXM6bmFtZXM6dGM6U0FNTDoyLjA6cHJvdG9jb2wiPjxtZDpLZXlEZXNjcmlwdG9yIHVzZT0ic2lnbmluZyI+PGRzOktleUluZm8geG1sbnM6ZHM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvMDkveG1sZHNpZyMiPjxkczpYNTA5RGF0YT48ZHM6WDUwOUNlcnRpZmljYXRlPk1JSUNsakNDQVg0Q0NRQzdSamtHUk9lS1BEQU5CZ2txaGtpRzl3MEJBUXNGQURBTk1Rc3dDUVlEVlFRR0V3SkRUakFlRncweU1EQTVNekF3TXpJeE5UQmFGdzB6TURBNU16QXdNekl4TlRCYU1BMHhDekFKQmdOVkJBWVRBa05PTUlJQklqQU5CZ2txaGtpRzl3MEJBUUVGQUFPQ0FROEFNSUlCQ2dLQ0FRRUF1aHovR0dyRExtMG9vWER0VEY0S1AwS1NGOWMvOU5wdnJyV1NPT2JOQS84em1vQzh3SUUwV0FNQ1FBUnRXZXp0bnlBUFZUOGwzNmhLMjN0bGREYXo5Q21BVVdCbVVmcXRJQm5JNkVGdDIwTUE0M2JsczNFT29NeE9qNjVaOTNtUXVsUHNRbjFOYVdLUTQ4Z0w2TFBGRFJQS010MlVCSCtzNkZiVEptWDIyS1lpTTZXR3FwNWxKcUplbHN3V1JWbTdDTGZnYmNVMytlQm81TDdMK00vR04weGNnYjlQYWpPM0wzYWQ3RkxMRnMyMFp1L2R5dGVZdkhEZFIwbjBQK3l2aHJrSGNhV1c1TXg4M3ZjcjJJaHZ0Z0pjWGVieTRkR2ZlcDRZbThXSlByVTRuNGFyVUNmellBb2hKa2xmR210RW9wN25jOCtsamt0RVpJRVliQjlMVndJREFRQUJNQTBHQ1NxR1NJYjNEUUVCQ3dVQUE0SUJBUUNCTHRZVWx2eEl6VEtFeHNqWmo2OTEvdnd4dnRsTG8zdzFDZjcrVlBQSENTMXpBbGlGSTRTMFU1VTQ2bWtpb2hndlBJK0dVUVRuUE9nRGo5bU42b1lRMTdBMGN6anFCd09VTWlQQ0NOTUtOK0ZNSEZYS2ZsVXd6SjVTTVdMMVM0elBOYW9tSWd1UkhES1dlTURhZ0FRdUFzcFVlMnRNNkdZOHFEQ1l3ZjV3MFRxVDB0MGwwWnNTdmJ3TXMrNis4S1dhMWtVTWNQNG1xRkVBNlhXbFVhRVpnSWowc3hnQzlZU005TTR0bFk1clZCODVlNFR3cnNrcEhTc3pNWmN5aUhOY3l1T28vQ1I2eFQ3MXkrNEpRcStiTXRHZVN2UDVRdzZuNm9HWnBTOXBhQjhENk1KU21wZ2lWVkZuWEJoUHBnV1BMeExtdXVGc2xhbmFzeVFqdlJFQjwvZHM6WDUwOUNlcnRpZmljYXRlPjwvZHM6WDUwOURhdGE+PC9kczpLZXlJbmZvPjwvbWQ6S2V5RGVzY3JpcHRvcj48bWQ6U2luZ2xlU2lnbk9uU2VydmljZSBCaW5kaW5nPSJ1cm46b2FzaXM6bmFtZXM6dGM6U0FNTDoyLjA6YmluZGluZ3M6SFRUUC1QT1NUIiBMb2NhdGlvbj0iaHR0cHM6Ly9jaGVuZ2NoYW8ubmFtZS9iNjVkNzZjZTQyNjAvIi8+PC9tZDpJRFBTU09EZXNjcmlwdG9yPjwvbWQ6RW50aXR5RGVzY3JpcHRvcj4="

ram_roles = {
  "roles"= [
    {
      # 注意：角色名不超过64个字符，允许英文字母、数字，或"-"
      "role_name"   = "admin"
      "description" = "成员账号的管理员角色"
    },
    {
      # 注意：角色名不超过64个字符，允许英文字母、数字，或"-"
      "role_name"   = "reader"
      "description" = "成员账号的只读角色"
    }
  ]
}

# Step2-3
user_name = "AutomationSecurity_DAT09"

# Step2-4
policy_name = "AliyunContributor"
policy_document = <<EOF
{
  "Version": "1",
  "Statement": [
    {
      "Action": [
        "RAM:*"
      ],
      "Resource": [
        "*"
      ],
      "Effect": "Deny"
    },
    {
      "Action": [
        "*"
      ],
      "Resource": [
        "*"
      ],
      "Effect": "Allow"
    }
  ]
}
EOF
attach_roles = ["admin"]
attach_users = ["AutomationSecurity_DAT09"]
reader_name = "reader"
reader_policy_type = "System"
reader_policy_name = "AliyunLogReadOnlyAccess"

# Step3-1
vpc_name = "autovpc1"
vswitch_name = "autovsw1"
# vpc_cidr_block = "172.16.0.0/12"
# switch_cidr_block = "172.16.0.0/21"
# zone_id = "cn-hangzhou-b"