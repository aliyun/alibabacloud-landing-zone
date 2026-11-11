# 工作空间统一初始化契约 v4

接口均属内置 `autowonder` MCP，绑定当前用户、会话空间；不能使用其他用户、空间或本机凭据。调用前核实统一初始化工具可用；不可用时报告需升级平台，不退回分段业务写入。

## 接口

| 工具（省略autowonder前缀） | 输入 | 输出 |
|---|---|---|
| list_harness_templates | 无 | templateId/name/description/version/sha256、变量数量 |
| get_harness_template | templateId/version | 元数据、variables、对象数量、sha256 |
| prepare_workspace_onboarding | 可选onboardingId、已有草稿必填expectedVersion、requestKey、draft | 创建或恢复的onboardingId、version、步骤清单；只保存准备数据 |
| get_workspace_onboarding | onboardingId；未提供则列出本人当前空间的可恢复引导摘要 | 阶段、配置版本、变量位置与填写状态、对象映射、步骤及缺口 |
| preview_workspace_initialization | onboardingId、expectedVersion、templateId/version/sha256、clientKind、variableValues、可选modelOverrides、configurationChanges | snapshotId/hash/expiresAt、summary、conflicts；一次冻结完整配置 |
| initialize_workspace | snapshotId/snapshotHash | runId、onboardingId、status；完整资料提交且预检通过后直接执行全部业务写入 |
| get_workspace_initialization | runId | 统一批次状态、对象映射、结果及失败/未执行项；成功时result.stage=RUNTIME_PENDING |
| resume_workspace_initialization | runId | 原runId和状态；直接恢复原批次，仅补未完成项 |
| check_workspace_readiness | onboardingId | checkId、配置版本、检查时间、PASS/BLOCKED/UNKNOWN/NOT_APPLICABLE证据及缺口 |

prepare及统一preview是显式允许的受限准备能力，只持久化草稿/快照，不能修改业务对象。draft只接受模板选择、客户提供的配置/证据引用、当前步骤及暂停/继续意图；不能直接设置业务步骤完成或READY，不能接受任意执行命令。更新采用版本锁、requestKey幂等。不得为这些工具开放其他直接写权限。

初始化预检必须接收完整 variableValues（完整 token 到非空最终字符串），拒绝未知、缺失及未解析占位符。快照冻结变量值，创建业务对象前在实例副本替换；母版与变量声明保持原样。旧快照恢复保持旧语义，不提供REPLACE/PRESERVE模式参数。服务端从可信发布包读取模板，不接受调用方任意模板正文、文件路径或URL。模板稳定ID、版本、摘要来自发布清单；官方Skill包与服务端目录由同一份母版生成。保持schemaVersion=2、多模板数组及完整字段校验，无SDLC角色保持null。

## 授权、快照与执行

预检和执行均校验实际用户对涉及领域的权限，不能借用Chief或后台管理员权限。快照冻结用户、空间、onboarding/config版本、模板版本、变更内容、复用对象版本和执行器配置；待执行有效期30分钟。初始化执行只传快照ID和hash，不再要求客户确认或行动计划，不重复传完整模板/变量值。模板更新不改变冻结内容。

同一快照只对应一个批次；状态QUEUED/RUNNING/SUCCEEDED/FAILED。子批次成功、工具已调用或入队不等于统一批次成功。创建对象与ID映射同事务，包存储按摘要对账，幂等键采用批次+对象稳定key。并发在引导版本及空间冲突对象范围内互斥；进程重启、超时、重复调用不重复创建或发布。

统一批次完成 Skill包/记录、SDLC/步骤、数字人配置/绑定、必要审核发布、小队/成员、默认执行器，以及仓库登记与依赖关系、逐角色仓库权限、部署Skill绑定及必要发布；服务端按依赖顺序执行。模板子批次成功不代表统一批次成功。已有同名同内容同绑定可复用，不同则冲突；不能凭名称判定相同。共享对象若后续需要修改，必须隔离成本实例副本并更新本实例引用，不能影响其他小队。

失败保留成果，无自动删除回滚；新统一批次直接使用runId恢复原快照，仅补未完成操作，不追加行动计划；已消费快照不受待执行期限限制。每步重验权限及对象版本；发现外部修改/删除则停止重新澄清。恢复不换模板、变量或模型；需要改变配置则重新预检，不能盲目重放旧批次。运行中暂停引导不强制取消已开始批次，允许其完成后停在下一步骤。

## 默认执行器

模板成员每人一个，不包含Chief，包含无SDLC角色。名称=数字人名称+“默认执行器”；显式配置contextWindow="1000000"、reasoningEffort="medium"、maxConcurrentDispatches=10、memoryMode="platform"。通过ExecutorService创建，不在初始化阶段注册/启动进程。

发布清单按稳定角色码声明模型族，当前AW_AUTO_CR→GLM Flash，其余→DeepSeek-Flash。clientKind 从 `list_executor_client_kinds` 返回的当前可创建类型中选择。再通过 `get_executor_launch_options` 查询所选类型的模型目录，确定最新可用版本并冻结ID、标签、来源和时间，不按显示名/列表顺序猜测。目录仅兜底、缺模型或版本不明确时先补齐；客户明确指定版本可通过modelOverrides按角色码记录原因，仍校验可用性。不得自动用auto/GLM主系列/DeepSeek-Pro替代。

冻结模型或固定配置不可用则报告失败，不静默换型。同数字人同名同配置可复用，差异冲突，保留其他执行器；全部默认执行器成功只是统一批次成功的必要条件，不替代仓库、权限、部署绑定及发布结果。

## 实例配置

`configurationChanges` 仅允许 `repositories`、`relations`、`repoPermissions`、`skillBindings`，不接受变量替换或任意工具/全量覆盖入口。完整变量通过同一预检的 `variableValues` 冻结并在创建实例前应用；不得拆为后续配置操作计划。记录变量位置、应用值及对象版本，不重新渲染母版覆盖客户修改。

逐仓库列出登记信息；关系明确 `from`/`to`；`repoPermissions` 必须明确每个仓库和每个模板角色的权限，使用模板角色码及已有模板权限矩阵，不遗漏成员。部署Skill以客户提供的已有名称引用，预检核实当前空间存在该 Skill 并冻结绑定名称，执行时解析实际对象；参数核实后写入部署配置。所有必需变量须完整或有明确不适用依据。共享Skill/SDLC修改前隔离，保留引用、交接、门禁、返工、人工出口及无需部署分支。必要审核发布纳入同一批次；已有用户草稿或待审核版本冲突时停止，不夹带发布。

仓库与关系使用RepoService，依赖方→被依赖方，详见repository-intake.md。READ：评审、澄清、PM；WRITE：开发、QA；新角色按职责确认，不授予ADMIN。已有权限差异必须在预检摘要中明确列出并实际更新，重复绑定接口忽略ID不表示权限已更新。平台权限不能代替远程Git授权。

部署Skill必须由客户提前提供且已完成必要审核发布，才允许初始化；读取真实包核实入口/参数。工程脚本部署不要求额外封装Skill。绑定QA并在对应SDLC显式调用，去除重复合并/构建职责；保留无部署分支。大包经现有存储上传，执行仅引用快照。秘密只使用受控引用，不写入正文/日志/聊天；不得运行包脚本、部署或迁移来验证配置。

## 引导进度与就绪

新统一流程从DRAFT进入INITIALIZING；统一批次全部业务配置及必要发布成功后，返回 `status=SUCCEEDED` 且 `result.stage=RUNTIME_PENDING`，表示空间配置初始化完成，不是READY，不表示执行器在线。失败保留实际进度和原批次。历史TEMPLATE_INITIALIZED/CONFIGURING阶段仅用于兼容已有记录。暂停为独立标志。单一可恢复记录直接复用，多条记录按实际需要选择，不能重复初始化。

客户固定看到2步：“提供初始化资料”（客户端选择卡及现有精简清单）、“一次完成空间配置”。后端steps及totalSteps/completedSteps/remainingSteps/currentStepIndex属于内部技术进度，不映射为客户步骤数。资料齐全只完成第1步，统一批次SUCCEEDED才完成第2步。执行器启动及就绪检查是初始化完成后的流程，不阻止如实报告配置初始化完成，也不虚报READY。执行前须以条件语句告知成功后的启动执行器、新建工单指引及失败/未知/未回复时发送“继续初始化”的恢复方式；执行后按真实统一批次结果报告。

历史批次仅使用旧get/resume工具查询或恢复，保留原快照与授权语义；新流程禁止调用initialize_harness、preview_workspace_configuration或apply_workspace_configuration拆分写入。统一工具不可用则停止并说明需要升级。

READY要求必要变量/引用/绑定/仓库权限已生效、所有成员默认执行器在线、Git访问和运行环境及部署只读连通证据有效。证据记录来源、时间、作用域及配置版本；客户提供有效证据可采纳，但未知不能算通过，不通过真实部署/迁移/工单验证。check只读不改READY；客户确认检查结果后，prepare引用checkId记录确认，服务端复核版本与最新在线状态后计算READY及最终步骤完成。后续配置改变使关联证据失效，恢复待办并撤回过期READY；不能只靠用户写入一个ready=true。

引导客户在选定主机使用平台启动指引上线默认执行器；不默认在Chief宿主执行，不自动安装依赖。启动凭据不写进普通记录或日志，使用平台现有受控领取方式。最终报告配置就绪与尚未执行的真实交付验证边界。

## 初始化后的启动指引

统一批次成功后，读取当前空间 Chief 在前置引导使用的执行器以及本次小队各默认执行器的实际启动命令，按执行器 ID 去重，按名称分段并用横线隔离，嵌入同一个可一键复制的本地 Agent 提示词。只展示统一后台管理方式，并提供包含 Chief 在内全部实际命令的本地 Agent 提示词。明确要求客户在运行 start 前先停止前置引导手动启动的执行器进程，保留平台执行器记录与凭据，避免重复连接。提示词要求生成后台 start/stop/status 管理脚本。启动与就绪确认合并为一个后续步骤；命令读取和脚本生成不等于平台在线，仍以 readiness 检查和服务端确认决定 READY。

启动脚本提示词须约束 Bash 3.2 兼容、sh 调用转入 Bash、变量花括号边界及 printf 分参数输出，禁止未引用的完整命令字符串分词执行。除语法检查外，本地 Agent 必须使用最终脚本及真实启动命令完成 start/status/重复 start/status/stop/status/start/status 全流程，遇到问题自动修复并完整复验，最终保持全部执行器后台运行，报告逐项结果和日志位置；无法解决的阻塞如实报告。验证前保存脚本及说明，核实并停止前置引导手动启动的清单内执行器进程，保留平台记录与凭据。
