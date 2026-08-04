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

Deployment details are in the
[community runtime guide](docs/community/README.md) and the
[Alibaba Cloud deployment Skill](skills/deploying-autowonder-on-alibaba-cloud/SKILL.md).
