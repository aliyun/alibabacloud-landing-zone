# Alibaba Cloud landing zones

[English version](./README_en.md)

阿里云企业 IT 治理云上样板间，针对不同阶段、不同规模的企业所设计的云治理最佳实践，帮助企业快速在阿里云上搭建一套安全、合规、可治理的云上环境。

## 样板间案例

我们目前提供以下样板间案例：

| **Name** | **Description** |
| ---- | ------------|
| [01 初创企业](./example/01-startup) | 针对初创企业的样板间 |
| [03 复杂企业](./example/03-complex-enterprise) | 针对复杂企业、跨国企业的样板间 |

## 小功能示例

| Name                                                         | Description                                                  |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| [01 初始化资源目录](./solution/IAM/01-terraform-init-resource-directory) | 为主账号开通资源目录，创建资源夹并在资源夹中创建资源账号     |
| [02 为资源目录下发策略](./solution/IAM/02-terraform-control-policy) | 创建新的管控策略，并绑定在指定资源夹下                       |
| [03 为企业成员账号创建角色](./solution/IAM/03-terraform-auto-create-role) | 在主账号和旗下所有成员账号中，创建用户指定的RAM角色          |
| [04 用户跨账号扮演角色](./solution/IAM/04-terraform-multi-roles) | 主账号扮演用户指定成员账号中的不同角色，并打印出所扮演角色的账号uid |

## 反馈

如果您遇到任何问题，请提交 Issues；如果您想参与改进项目，请提交 PR。

## 参与开发

提交 PR 时，您需要同意阿里巴巴的 CLA 协议，否则 PR 不会被合并。