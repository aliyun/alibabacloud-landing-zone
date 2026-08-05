# AutoWonder Community

AutoWonder is a multi-agent software delivery platform. The community runtime is
self-contained and does not require Alibaba internal KMS, bootstrap, security,
artifact, or package infrastructure.

Deployment prerequisites, configuration, and verification commands are in the
[community runtime guide](docs/community/README.md).

## Recommended Deployment / 推荐部署架构

The recommended topology is a multi-zone HA deployment with two application
nodes and private data services.

```mermaid
flowchart TB
    User["Users / 用户"]
    Runtime["Agent executors / 数字人执行器"]

    subgraph Cloud["Alibaba Cloud VPC / 阿里云 VPC"]
        NLB["Public NLB<br/>TCP 443 + 7001"]
        App["AutoWonder application cluster<br/>2 ECS across 2 availability zones"]
        RDS["MySQL 8 HA"]
        Redis["Redis 7 Multi-zone"]
        OSS["Private OSS<br/>Package + Artifact buckets"]
        SLS["SLS<br/>System + Business + Metrics"]
    end
    AppRAM["Application RAM<br/>OSS + SLS least privilege"]

    User --> NLB
    Runtime -->|"WS/WSS"| NLB
    NLB --> App
    App --> RDS
    App --> Redis
    App --> OSS
    App --> SLS
    AppRAM -.-> OSS
    AppRAM -.-> SLS
```

## Collaboration Model / 平台工作模式

Users collaborate through the native work item platform, DingTalk groups, or a
personal Agent client connected through AutoWonder MCP.

```mermaid
flowchart LR
    Human["Team member / 团队成员"]
    WorkitemUI["AutoWonder work items / 工单平台"]
    DingTalk["DingTalk group / 钉钉群"]
    AgentClient["Agent client + AutoWonder MCP"]
    Bot["DingTalk bot / 协作机器人"]
    Platform["AutoWonder platform"]
    Orchestrator["SDLC orchestration / 流程编排"]
    DigitalWorker["Digital workers / 数字人小队"]
    Executor["Online executor / 在线执行器"]

    Human --> WorkitemUI
    Human --> DingTalk
    Human --> AgentClient
    WorkitemUI <--> Platform
    DingTalk <--> Bot
    Bot <--> Platform
    AgentClient <--> Platform
    Platform --> Orchestrator
    Orchestrator --> DigitalWorker
    DigitalWorker <--> Executor
    Executor -->|"Streaming results / 流式结果"| Platform
    Platform -->|"Progress, blockers, approvals / 主动通知"| Bot
```

## Model Guidance / 模型建议

| Digital workers | Recommended model | Context |
| --- | --- | --- |
| Development, Testing, Code Review / 开发、测试、CR | `Qwen3.8-Max` | 1M |
| DBA, Conflict Resolution, Requirement Clarification / DBA、冲突解决、需求澄清 | `GLM-5.2`, `DeepSeek-V4 Pro`, or `Qwen3.7-Max` | 400K |

Use the equivalent model name provided by the selected executor.

## Getting Started / 系统上手

1. Create an organization, then register the code repository / 创建组织后先托管代码仓库。
2. Start with the initialized `SDLC - 样板间` templates / 使用初始化的 SDLC 样板间。
3. Open **Avatar -> Personal Settings -> MCP Tokens** and connect the token to a personal Agent client / 在个人设置中创建 MCP 并挂载到本地 Agent 客户端。

Deployment details are in the
[community runtime guide](docs/community/README.md) and the
[Alibaba Cloud deployment Skill](skills/deploying-autowonder-on-alibaba-cloud/SKILL.md).
