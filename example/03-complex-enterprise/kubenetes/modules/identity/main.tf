data "alicloud_log_service" "open" {
    enable = "On"
}

# 业务账号容器服务角色授权
resource "alicloud_ram_role" "ram_role_kubernetes" {
  for_each = {
    for ram_role in var.roles : ram_role.name => ram_role
  }
  name        = each.value.name
  document    = local.role_document
  description = each.value.name
  force       = true
}

resource "alicloud_ram_policy" "ram_policy_kubernetes" {
  for_each = {
    for policy in var.policys : policy.name => policy
  }
  name        = each.value.name
  document    = each.value.document
  description = each.value.description
  force       = true
}

resource "alicloud_ram_role_policy_attachment" "ram_attach" {
 for_each = {
    for attach in var.rolesAttachPolicy : attach.policy => attach
  }
  policy_name = each.value.policy
  policy_type = "Custom"
  role_name   = each.value.name
}

locals{
    role_document=<<EOF
    {
        "Statement": [
            {
                "Action": "sts:AssumeRole",
                "Effect": "Allow",
                "Principal": {
                    "Service": [
                        "cs.aliyuncs.com"
                    ]
                }
            }
        ],
        "Version": "1"
        }
    EOF
}
