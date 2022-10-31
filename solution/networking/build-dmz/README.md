
# 概述
在使用CEN-TR基础组网已经完成的基础上进行搭建，示例代码在DMZ VPC中创建NAT、ALB等出入口资源并支持进行路由配置。

## 使用限制
1. 由于TF支持原因，目前无法使用跨VPC挂载IP类型服务器组
2. 由于API及TF支持原因，目前无法使用TF自动获取ALB回源路由，代码仅做配置示例

## 搭建方式
需要本地环境完成Terraform基础配置、支持阿里云资源创建，完成填写settings.tfvars参数配置后执行命令：
```terraform
terraform init
terraform plan --var-file=settings.tfvars
terraform apply --var-file=settings.tfvars
```

## Terraform要求

| Name                                                                            | Version     |
|---------------------------------------------------------------------------------|-------------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform)       | \>= 1.1.7   |
| <a name="requirement_alicloud"></a> [alicloud provider](#requirement\_alicloud) | \>= 1.160.0 |


## 相关内容
* [Terraform-Provider-Alicloud Github](https://github.com/aliyun/terraform-provider-alicloud)
* [Terraform-Provider-Alicloud Release](https://releases.hashicorp.com/terraform-provider-alicloud/)
* [Terraform-Provider-Alicloud Docs](https://registry.terraform.io/providers/aliyun/alicloud/latest/docs)
