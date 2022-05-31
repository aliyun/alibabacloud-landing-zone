# terraform-alicloud-landing-zone-cloud-sso-multi-account-policy

本 Module 用于阿里云 Cloud SSO 的多账号权限管理

## 用法

```terraform
module "cloud_sso" {
  source = "terraform-alicloud-modules/landing-zone-cloud-sso-multi-account-policy/alicloud"

  create_directory                = false
  directory_id                    = data.alicloud_cloud_sso_directories.default.ids.0
  create_resource_manager_account = true
  display_name                    = "xxappnamedev"
  
  create_resource_manager_folder = true
  folder_name                    = "appname"

  assign_access_configuration    = true
}
```

## 资源目录

成员账号在创建的时候可以添加到一个资源目录中，本 Module 支持通过设置如下的参数自动查询资源目录:

```terraform
parent_folder_id = "rd-xxxxx"
folder_name      = "appname"
```

同时，可以通过指定如下的参数自动创建一个新的资源目录，并将成员账号添加到这个新的资源目录中：

```terraform
create_resource_manager_folder = true
parent_folder_id               = "rd-xxxxx"
folder_name                    = "appname"
```

## 成员账号

本 Module 支持通过如下的参数获取设置存量的成员账号：

```terraform
create_resource_manager_account = false
account_id                      = "rd-xxxxx"
```

同时，本 Module 也支持创建一个新的成员账号：

```terraform
create_resource_manager_account = true
display_name                    = var.display_name
payer_account_id                = var.payer_account_id
```

## 用户组和访问配置

在多账号权限管理场景中，每个成员账号需要依次指定用户组和访问配置。为了让多账号管理更便捷，权限配置遵循如下的设置规则：

1. 用户组名称必须符合格式`<用户组前缀>-<成员账号名称>-<访问配置名称>`，比如 "ALIYUN-foo-acName", "ALIYUN-foo-acName1", "ALIYUN-bar-acName"
2. 本 Module 将会通过字段`cloud_sso_group_name_regex`自动查询和过滤用户组，如果这个字段值为空，那么本 Module 将自动拼接一个默认值`".*<成员账号名称>"`
3. 如果存在符合条件的用户组，本 Module 将自动将成员账号添加到用户组中，比如账号"foo"将自动被添加到用户组"ALIYUN-foo-acName"和"ALIYUN-foo-acName1"中
4. 此外，本 Module 将所有符合条件的用户组按照`<成员账号名称>-`进行分割，然后用分割后的最后一部分内容去过滤所有的访问配置。
   比如，"ALIYUN-foo-acName"将被分割为"ALIYUN-" 和 "acName"，然后按照"acName"过滤所有的访问配置。
5. 如果存在符合条件的访问配置，并且设置 `assign_access_configuration = true`，本 Module 将自动将访问配置添加到对应的用户组中。
   比如，访问配置"acName"将本添加到组"ALIYUN-foo-acName"中。

目录是Cloud SSO中的实例。目录ID是所有Cloud SSO其他资源的前提条件。所以在使用本Module的时候，需要通过参数 `directory_id` 来指定一个目录：

```terraform
directory_id = "rd-ywxxxxx"
```

如果参数 `directory_id` 值为空，本Module将会自动查询当前账号下的目录。

除此之外，本 Module 还支持创建一个新的Cloud SSO目录，具体的参数如下：

```terraform
create_directory            = true
directory_name              = "my-directory"
mfa_authentication_status   = "Enabled"
scim_synchronization_status = "Enabled"
```

## 示例

- [完整示例](https://github.com/terraform-alicloud-modules/terraform-alicloud-cloud-sso-multi-account-policy/tree/master/examples/complete)

## 贡献

如果有任何问题，可以直接在此提交 [issues](https://github.com/terraform-alicloud-modules/terraform-alicloud-cloud-sso-multi-account-policy/issues/new).

<!-- BEGINNING OF PRE-COMMIT-TERRAFORM DOCS HOOK -->
## Terraform 版本

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 0.13.1 |
| <a name="requirement_alicloud"></a> [alicloud](#requirement\_alicloud) | >= 1.145.0 |

## 文档

本Module已经被发布到 Terraform Registry 上了，详细请看[文档](https://registry.terraform.io/modules/terraform-alicloud-modules/cloud-sso-multi-account-policy/alicloud/latest)

## 作者

Created and maintained by Alibaba Cloud Terraform Team(terraform@alibabacloud.com)

## 协议

MIT License. See LICENSE for full details.

## 参考

* [Terraform-Provider-Alicloud Github](https://github.com/aliyun/terraform-provider-alicloud)
* [Terraform-Provider-Alicloud Release](https://releases.hashicorp.com/terraform-provider-alicloud/)
* [Terraform-Provider-Alicloud Docs](https://registry.terraform.io/providers/aliyun/alicloud/latest/docs)

