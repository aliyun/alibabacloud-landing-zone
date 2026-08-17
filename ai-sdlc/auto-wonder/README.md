# AutoWonder Community

AutoWonder is a multi-agent software delivery platform. The community runtime is
self-contained and does not require Alibaba internal KMS, bootstrap, security,
artifact, or package infrastructure.

Deployment prerequisites, configuration, and verification commands are in the
[community runtime guide](docs/community/README.md).

## Recommended Deployment / 推荐部署架构

The recommended topology is a multi-zone HA deployment with two application
nodes and private data services.

![AutoWonder recommended deployment architecture](docs/images/autowonder-deployment-architecture.png)

## Collaboration Model / 平台工作模式

Users collaborate through the native work item platform, DingTalk groups, or a
personal Agent client connected through AutoWonder MCP.

![AutoWonder collaboration model](docs/images/autowonder-collaboration-model.png)

## Model Guidance / 模型建议

| Digital workers | Recommended model | Context |
| --- | --- | --- |
| Development, Testing, Code Review / 开发、测试、CR | `Qwen3.8-Max` | 1M |
| DBA, Conflict Resolution, Requirement Clarification / DBA、冲突解决、需求澄清 | `GLM-5.2`, `DeepSeek-V4 Pro`, or `Qwen3.7-Max` | 400K |

Use the equivalent model name provided by the selected executor.

## Getting Started / 系统上手

![Get started with AutoWonder](docs/images/autowonder-getting-started.png)

Deployment details are in the
[community runtime guide](docs/community/README.md) and the
[Alibaba Cloud deployment Skill](skills/deploying-autowonder-on-alibaba-cloud/SKILL.md).

## Roadmap / 路线图

![AutoWonder Community roadmap](docs/images/autowonder-community-roadmap.png)
