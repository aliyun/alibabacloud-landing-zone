# 平台智能能力内部调用

`PlatformIntelligenceService` 将当前工作空间的系统平台智能体（`kind=PLATFORM`，出厂为 Chief of Staff）封装为 Spring 内部服务。业务模块无需知道数字员工 ID、执行器 ID 或 WebSocket 协议，即可查询可用状态、提交智能请求、读取结果。

调用使用员工当前在线版本的身份与已配置能力，沿用现有会话打包、运行时调度、持久化及失败恢复机制。没有新增模型配置，也没有直接绕过数字员工调用大模型。

## 接口

包名：`com.aliyun.autowonder.agent.PlatformIntelligenceService`。

| 方法 | 用途 |
| --- | --- |
| `getStatus(Long workspaceId)` | 只读查询当前能力是否可调度 |
| `invoke(Long workspaceId, UUID requestId, String prompt)` | 异步提交一次独立请求，返回调用记录 |
| `getResult(Long workspaceId, UUID requestId)` | 查询持久化状态、回复及错误；不存在时返回 `null` |

`workspaceId` 是工作空间 ID，对应现有数据表的 `tenant_id`。所有入口要求正数 ID；调用和结果查询还要求非空 UUID，调用要求非空白 prompt。

这是可信后端模块使用的内部服务。调用方必须先完成业务权限校验，从可信的登录上下文或后台任务记录取得工作空间 ID。不要把用户任意传入的 ID 直接交给此服务。智能执行继承现有员工的能力与权限配置，提示词中只应包含该工作空间允许处理的数据。

## 调用示例

通过构造器注入服务，例如：

```java
@Service
public class SummaryService {
    private final PlatformIntelligenceService intelligence;

    public SummaryService(PlatformIntelligenceService intelligence) {
        this.intelligence = intelligence;
    }

    public PlatformIntelligenceService.InvocationResult submit(
            Long workspaceId, UUID requestId, String text) {
        return intelligence.invoke(workspaceId, requestId,
                "请归纳以下材料的主要结论和待办事项：\n" + text);
    }
}
```

业务侧为一次逻辑请求生成 `UUID.randomUUID()` 并保存。提交后，在**提交事务结束后**使用后台任务或后续请求查询：

```java
var result = intelligence.getResult(workspaceId, requestId);
if (result == null) {
    // 当前工作空间没有该调用记录。
} else if (!result.isTerminal()) {
    // 尚未结束，稍后再次查询；不要在请求线程中无限等待。
} else if ("SUCCESS".equals(result.status())) {
    String markdown = result.content();
    // 消费回复。模型输出需要结构化使用时，应由业务侧解析和校验。
} else {
    String reason = result.error();
    // FAILED 或 CANCELED，交给业务侧处理。
}
```

返回字段：`requestId`、`conversationId`、`turnId`、`status`、`content`、`error`。运行中通常为 `PROCESSING`（底层也支持 `QUEUED`），终态为 `SUCCESS`、`FAILED`、`CANCELED`。回复为文本/Markdown；失败或取消时可能带部分内容，应以状态判断是否成功。

同一工作空间内，同一请求 ID 和相同 prompt 的顺序重试会读取已有结果，不再提交新任务，即使员工随后离线仍可读取。已存在的请求 ID 换用不同 prompt 会报参数错误。不同业务请求使用不同 UUID；并发调用也应各用独立 UUID，不应并发提交同一 ID。需要对失败请求重新执行时，生成新 UUID。

每次逻辑请求独占一个 `PLATFORM_INTERNAL` 会话，不共享 IM、工单澄清或其他请求的聊天历史。内部调用的身份提示词会附加 API 模式约束，要求直接文字回复，不使用需要用户回答的交互工具。

## 可用状态与离线处理

`CapabilityStatus` 包含 `agentId`、`status`，`isAvailable()` 仅在 `AVAILABLE` 时为 `true`。

| status | 含义 |
| --- | --- |
| `AVAILABLE` | 员工配置在线，存在有效在线版本和可调度执行器 |
| `NOT_CONFIGURED` | 当前工作空间没有有效的平台员工 |
| `AGENT_OFFLINE` | 员工业务状态不是 ONLINE |
| `VERSION_UNAVAILABLE` | 在线版本缺失、归属不匹配或缺少身份提示词 |
| `RUNTIME_UNAVAILABLE` | 没有可调度执行器，可能离线、容量已满或处于恢复隔离/冷却期 |

状态查询不创建员工、会话或任务，也不推进执行器轮询游标。`RUNTIME_UNAVAILABLE` 不等价于员工配置下线。

新请求不可用时，`invoke` 抛出 `PlatformIntelligenceService.UnavailableException`，通过 `getCapabilityStatus()` 读取原因，不创建待执行会话。调用方可以展示暂不可用、稍后重试或选择自己的降级逻辑。

状态是瞬时快照，不预留容量，也不能保证模型供应商健康。状态检查后运行时仍可能掉线；实际提交或后续执行可能失败。底层调度/存储异常继续向调用方传播，不伪装成成功；提交结果不确定时使用原请求 ID 查询或顺序重试。已提交轮次沿用现有恢复机制，业务方应设置自己的等待截止时间；停止轮询不代表取消执行。不要在包裹 `invoke` 的未提交事务中等待回复，实际下发发生在事务提交后。

## HTTP 状态接口

```http
GET /api/platform-intelligence/status
```

复用项目登录态、当前工作空间上下文和 `READ_ONLY` 工作空间访问权限，返回标准 `Result<CapabilityStatus>` 包装。例如其 `data` 部分为：

```json
{
  "agentId": 123,
  "status": "RUNTIME_UNAVAILABLE",
  "available": false
}
```

客户端不传 workspaceId。本次仅公开只读状态 HTTP 接口；智能调用与结果查询通过上述 Spring 内部服务供业务模块集成。

## 实现与验证

- 服务：`src/main/java/com/aliyun/autowonder/agent/PlatformIntelligenceService.java`
- 状态接口：`src/main/java/com/aliyun/autowonder/agent/PlatformIntelligenceController.java`
- 内部会话渠道：`src/main/java/com/aliyun/autowonder/conversation/PlatformIntelligenceChannelSink.java`
- 复用现有会话表，无新增数据库迁移；结果已由会话服务持久化，内部渠道无需向 IM 发送消息。

相关回归测试：

```bash
mvn -q -DskipFrontend=true -DskipGitCommitId=true \
  -Dtest=PlatformIntelligenceServiceTest,PlatformIntelligenceControllerTest,AgentConversationServiceTest,ExecutorSelectorTest test
```

测试覆盖状态分类、离线拒绝、顺序重试、工作空间和渠道隔离、终态结果读取、内部会话 API 模式和只读调度探测。
