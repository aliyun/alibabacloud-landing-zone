package com.aliyun.autowonder.common.error;

public enum ErrorCode {
    SUCCESS("0", "success"),
    // 10xxx 通用/认证
    SYSTEM_ERROR("10000", "系统内部错误"),
    PARAM_INVALID("10001", "参数不合法"),
    UNAUTHORIZED("10401", "未登录或登录已失效"),
    NO_PERMISSION("10403", "无权限"),
    NOT_FOUND("10404", "资源不存在"),
    CONFLICT("10409", "数据冲突"),
    RATE_LIMITED("10429", "请求过于频繁"),
    // 11xxx 工作空间
    WORKSPACE_NOT_MEMBER("11001", "非该工作空间成员"),
    WORKSPACE_NAME_REQUIRED("11002", "工作空间名不能为空"),
    WORKSPACE_NAME_DUPLICATE("11003", "工作空间名称已存在"),
    ORG_VERSION_CONFLICT("11004", "工作空间已被修改，请重试"),
    ORG_DELETED_OR_DISABLED("11005", "工作空间已删除或已停用"),
    // 回收站可见性统一用这一个码：区分“不存在”和“无权访问”会让调用方通过枚举 id
    // 探测出别人工作空间的存在性。
    ORG_NOT_FOUND_OR_NO_PERMISSION("11006", "工作空间不存在或无权访问"),
    ORG_RESTORE_NAME_CONFLICT("11007", "已存在同名的在用工作空间，请改名后再恢复"),
    // 12xxx 工作空间访问等级
    WORKSPACE_ACCESS_LEVEL_INVALID("12007", "工作空间访问级别不合法"),
    WORKSPACE_ACCESS_INSUFFICIENT("12008", "工作空间访问级别不足"),
    WORKSPACE_OWNER_MUTATION_PROTECTED("12009", "工作空间所有者不可直接修改"),
    WORKSPACE_SELF_LEVEL_MUTATION_FORBIDDEN("12010", "不可修改自己的工作空间访问级别"),
    WORKSPACE_OWNER_TRANSFER_INVALID("12011", "工作空间所有者转让不合法"),
    WORKSPACE_ACCESS_REQUEST_DUPLICATE("12012", "已有待审批的申请"),
    WORKSPACE_ACCESS_REQUEST_ALREADY_MEMBER("12013", "已是该工作空间成员"),
    WORKSPACE_ACCESS_REQUEST_NOT_FOUND("12014", "权限申请记录不存在"),
    WORKSPACE_ACCESS_REQUEST_LEVEL_INVALID("12015", "申请的权限级别不合法"),
    WORKSPACE_ACCESS_REQUEST_NOT_REQUESTER("12016", "仅申请人本人可撤销申请"),
    WORKSPACE_ACCESS_REQUEST_NOT_PENDING("12017", "该申请已不在待审核状态，无法撤销"),
    // 13xxx 状态机/工单/澄清
    WORK_TYPE_INVALID("13001", "工单类型不合法"),
    STATUS_TEMPLATE_NOT_FOUND("13002", "未找到默认状态模版"),
    WORKITEM_NOT_FOUND("13003", "工单不存在"),
    ILLEGAL_TRANSITION("13004", "非法状态流转"),
    WORKITEM_VERSION_CONFLICT("13005", "工单已被修改,请重试"),
    WORKITEM_EXTERNAL_NO_DELETE("13006", "外部平台集成工单不可删除"),
    WORKITEM_RUNNING_NO_DELETE("13007", "工单正在执行中，请等待完成或结束后再删除"),
    WORKITEM_EXTERNAL_CONTENT_READ_ONLY("13008", "外部工单的标题和描述由来源平台维护"),
    STATUS_TEMPLATE_NAME_REQUIRED("13010", "模版名称不能为空"),
    STATUS_TEMPLATE_WORK_TYPE_REQUIRED("13011", "工单类型不能为空"),
    STATUS_TEMPLATE_DELETE_IN_USE("13012", "模版被工单引用,无法删除"),
    STATUS_NODE_CODE_REQUIRED("13013", "状态编码不能为空"),
    STATUS_NODE_NAME_REQUIRED("13014", "状态名称不能为空"),
    STATUS_NODE_CATEGORY_REQUIRED("13015", "状态分类不能为空"),
    STATUS_NODE_CODE_DUPLICATE("13016", "同模版下状态编码重复"),
    STATUS_NODE_DELETE_IN_USE("13017", "有工单处于该状态,无法删除"),
    STATUS_NODE_NOT_FOUND("13018", "状态节点不存在"),
    STATUS_TRANSITION_NOT_FOUND("13019", "流转规则不存在"),
    STATUS_TRANSITION_DUPLICATE("13020", "相同起止节点的流转已存在"),
    STATUS_TRANSITION_NAME_REQUIRED("13021", "流转名称不能为空"),
    STATUS_TEMPLATE_VERSION_CONFLICT("13022", "模版已被修改,请重试"),
    // 14xxx 数字员工/Agent
    AGENT_NOT_FOUND("14001", "数字员工不存在"),
    AGENT_VERSION_NOT_FOUND("14002", "版本不存在"),
    AGENT_VERSION_CONFLICT("14003", "版本已被修改,请重试"),
    AGENT_NOT_DRAFT("14004", "当前版本不是草稿,无法编辑"),
    AGENT_NOT_PENDING("14005", "当前版本未处于待审核状态"),
    AGENT_NOT_ONLINE("14006", "数字员工未上线"),
    AGENT_ROLLBACK_TARGET_INVALID("14007", "回退目标必须是已通过的历史版本"),
    AGENT_NAME_REQUIRED("14008", "员工名称不能为空"),
    AGENT_ONLINE_NO_DELETE("14009", "数字员工在线中,请先下线再删除"),
    AGENT_NOT_OFFLINE("14010", "数字员工不是下线状态,无法重新上线"),
    AGENT_ONLINE_NO_APPROVED_VERSION("14011", "没有可用的已通过版本,无法重新上线"),
    AGENT_PLATFORM_NO_DELETE("14012", "平台智能体不可删除"),
    AGENT_PLATFORM_NO_OFFLINE("14013", "平台智能体不可下线"),
    AGENT_PLATFORM_LOCKED("14014", "平台智能体的名称与 SDLC 配置不可修改"),
    AGENT_PLATFORM_REPO_LOCKED("14015", "平台智能体默认拥有全部仓库的只读权限，仓库配置不可修改"),
    // 15xxx 小队/Squad
    SQUAD_NOT_FOUND("15001", "小队不存在"),
    SQUAD_NAME_REQUIRED("15002", "小队名称不能为空"),
    SQUAD_MEMBER_DUPLICATE("15003", "该成员已在小队中"),
    SQUAD_TEMPLATE_NOT_FOUND("15010", "小队模版不存在"),
    // 16xxx SDLC 编排
    SDLC_NOT_FOUND("16001", "SDLC流程不存在"),
    SDLC_NAME_REQUIRED("16002", "流程名称不能为空"),
    SDLC_NOT_DRAFT("16003", "流程非草稿状态,无法编辑结构"),
    SDLC_ENABLE_NO_STEPS("16004", "流程至少需要一个步骤才能启用"),
    SDLC_ENABLE_INVALID_TARGET("16006", "步骤流转目标不属于本流程"),
    SDLC_ENABLE_STATUS_INCOMPATIBLE("16007", "步骤状态映射与状态模版不兼容"),
    SDLC_ALREADY_ENABLED("16008", "流程已启用"),
    SDLC_NOT_ENABLED("16009", "流程未启用,无法停用"),
    SDLC_DELETE_IN_USE("16010", "流程被引用,无法删除"),
    SDLC_STEP_NOT_FOUND("16011", "步骤不存在"),
    SDLC_STEP_ORDER_DUPLICATE("16012", "步骤序号重复"),
    SDLC_VERSION_CONFLICT("16013", "流程已被修改,请重试"),
    // 17xxx 执行器/产物/任务包
    EXECUTOR_NOT_FOUND("17001", "执行器不存在"),
    EXECUTOR_NAME_REQUIRED("17002", "执行器名称不能为空"),
    EXECUTOR_AGENT_MISMATCH("17003", "执行器不属于该数字员工"),
    EXECUTOR_TOKEN_NOT_RETRIEVABLE("17004", "该执行器的 Token 不可回显，请重新生成"),
    EXECUTOR_LAUNCH_CONFIG_VERSION_CONFLICT("17005", "启动配置已被修改，请刷新后重试"),
    EXECUTOR_LAUNCH_CONFIG_MODEL_INVALID("17006", "模型不可用，请重新选择"),
    EXECUTOR_LAUNCH_CONFIG_INCOMPLETE("17007", "执行器启动配置不完整，请先保存启动配置后再生成命令"),
    EXECUTOR_LAUNCH_CONFIG_OVERRIDE_REJECTED("17008",
            "启动配置以数据库为准，不支持临时覆盖；请调用 autowonder.update_executor_launch_config 修改后再生成命令"),
    EXECUTOR_CLIENT_KIND_INVALID("17009", "客户端类型不合法，仅支持 QODER_CLI/QODER_CN_CLI"),
    ARTIFACT_NOT_FOUND("17010", "产物不存在"),
    EXECUTOR_CLIENT_KIND_MISSING("17011", "执行器缺少客户端类型，无法生成启动命令"),
    PACKAGE_BUILD_FAILED("17020", "任务包打包失败"),
    STORAGE_ERROR("17021", "对象存储操作失败"),
    // 1703x 调度
    DISPATCH_NOT_FOUND("17030", "调度记录不存在"),
    // 18xxx WebSocket/执行器连接
    WS_TOKEN_MISSING("18001", "缺少执行器Token"),
    WS_TOKEN_INVALID("18002", "执行器Token无效"),
    WS_EXECUTOR_DELETED("18003", "执行器已删除"),
    // 19xxx AI 引擎
    AI_SESSION_NOT_FOUND("19001", "AI会话不存在"),
    AI_SESSION_NOT_RUNNING("19002", "AI会话非运行状态"),
    AI_SESSION_NOT_WAIT_USER("19003", "AI会话非待确认状态"),
    AI_CONFIRM_VALIDATION_FAILED("19004", "AI结果校验失败"),
    AI_SCENE_NOT_SUPPORTED("19005", "不支持的AI场景"),
    AI_QUEUE_FULL("19006", "AI队列已满,请稍后重试"),
    AI_CLI_TIMEOUT("19007", "AI调用超时"),
    AI_CLI_FAILED("19008", "AI调用失败"),
    // 20xxx 仓库中心
    REPO_NOT_FOUND("20001", "仓库不存在"),
    REPO_NAME_REQUIRED("20002", "仓库名称不能为空"),
    REPO_URL_REQUIRED("20003", "仓库地址不能为空"),
    REPO_DELETE_IN_USE("20004", "仓库被数字员工引用,无法删除"),
    REPO_VERSION_CONFLICT("20005", "仓库已被修改,请重试"),
    REPO_CONCLUSION_NOT_FOUND("20006", "仓库结论不存在"),
    REPO_RELATION_DUPLICATE("20007", "仓库关系已存在"),
    REPO_RELATION_SELF_REF("20008", "仓库不能与自身建立关系"),
    REPO_RELATION_NOT_FOUND("20009", "仓库关系不存在"),
    REPO_ALREADY_SCANNING("20010", "仓库正在扫描中"),
    // 21xxx 记忆中心
    MEMORY_NOT_FOUND("21001", "记忆不存在"),
    MEMORY_TITLE_REQUIRED("21002", "记忆标题不能为空"),
    MEMORY_DELETE_IN_USE("21003", "记忆被数字员工引用,无法删除"),
    MEMORY_VERSION_CONFLICT("21004", "记忆已被修改,请重试"),
    MEMORY_NOT_PENDING("21005", "记忆非待审核状态"),
    MEMORY_ALREADY_REVIEWED("21006", "记忆已被审核"),
    MEMORY_SCOPE_CHANGE_NOT_ADOPTED("21007", "仅已采纳记忆支持变更范围"),
    // 22xxx 技能中心
    SKILL_NOT_FOUND("22001", "技能不存在"),
    SKILL_NAME_REQUIRED("22002", "技能名称不能为空"),
    SKILL_TYPE_REQUIRED("22003", "技能类型不能为空"),
    SKILL_DELETE_IN_USE("22004", "技能被数字员工引用,无法删除"),
    SKILL_VERSION_CONFLICT("22005", "技能已被修改,请重试"),
    SKILL_DUPLICATE_NAME("22006", "同类型同名技能已存在"),
    // 23xxx 通知中心
    NOTIFICATION_NOT_FOUND("23001", "通知不存在"),
    // 24xxx 审计
    AUDIT_EXPORT_NOT_FOUND("24001", "导出任务不存在"),
    // 25xxx 系统设置
    SETTING_NOT_FOUND("25001", "设置项不存在"),
    SETTING_GROUP_INVALID("25002", "设置分组不合法"),
    SETTING_SECRET_READ_DENIED("25003", "密钥项不可明文读取"),
    // 26xxx AI 用量/配额
    AI_QUOTA_EXCEEDED("26001", "AI配额已用尽,请升级或等待周期重置"),
    // 27xxx MCP/外部 AI 接管
    MCP_TOKEN_NOT_FOUND("27001", "MCP Token不存在"),
    MCP_TOOL_NOT_FOUND("27002", "MCP工具不存在"),
    MCP_TOOL_ARGUMENT_INVALID("27003", "MCP工具参数不合法"),
    // 28xxx 平台 IM 通知
    IM_CHANNEL_NOT_READY("28001", "平台 IM 机器人配置不完整或未就绪"),
    IM_IDENTITY_NOT_CONFIGURED("28002", "尚未配置 IM 工号"),
    IM_TEST_SEND_FAILED("28003", "IM 测试消息发送失败"),
    // 29xxx 账号注销
    DEACTIVATION_ALREADY_PENDING("29001", "账号注销申请已提交，当前处于冷静期"),
    DEACTIVATION_BLOCKED_BY_WORKITEMS("29002", "存在未完结的工单，请先处理后再申请注销"),
    DEACTIVATION_BLOCKED_BY_SOLE_ADMIN("29003", "您是某工作空间的唯一管理员，请先转让管理权后再申请注销"),
    DEACTIVATION_NOT_PENDING("29004", "当前没有进行中的注销申请"),
    DEACTIVATION_ALREADY_REVOKED("29005", "注销申请已撤销"),
    DEACTIVATION_CONFIRM_MISMATCH("29006", "确认输入不匹配，请重新输入用户名确认"),
    DEACTIVATION_ACCOUNT_DISABLED("29007", "该账号已注销，无法登录"),
    // 30xxx 定时任务
    SCHEDULED_TASK_NOT_FOUND("30001", "定时任务不存在"),
    SCHEDULED_TASK_VERSION_CONFLICT("30002", "定时任务已被修改，请重试"),
    SCHEDULED_TASK_CRON_INVALID("30003", "Cron 表达式或时区不合法"),
    SCHEDULED_TASK_VALIDATION_FAILED("30004", "定时任务参数不合法"),
    SCHEDULED_TASK_INVALID_STATE("30005", "定时任务当前状态不允许该操作"),
    SCHEDULED_TASK_SCHEMA_NOT_READY("30006", "当前环境尚未完成 7×24 能力升级"),
    // 31xxx 平台管理员
    SYSTEM_ADMIN_USER_REQUIRED("31001", "用户不能为空"),
    SYSTEM_ADMIN_TARGET_NOT_FOUND("31002", "用户不存在或已不可用"),
    SYSTEM_ADMIN_SELF_REMOVAL_FORBIDDEN("31003", "平台管理员不可移除自己"),
    SYSTEM_ADMIN_LAST_ONE_FORBIDDEN("31004", "平台管理员至少保留一名，无法移除最后一名"),
    SYSTEM_ADMIN_TARGET_NOT_ADMIN("31005", "该用户不是平台管理员"),
    // 32xxx 平台管家对话
    // 与 ORG_NOT_FOUND_OR_NO_PERMISSION 同理：合并「不存在」和「无权访问」，
    // 否则任何人都能拿会话 id 枚举出别人聊过什么。
    PLATFORM_CONVERSATION_NOT_FOUND_OR_NO_PERMISSION("32001", "会话不存在或无权访问"),
    PLATFORM_CONVERSATION_OWNER_ONLY("32002", "该操作仅会话创建者本人可执行"),
    PLATFORM_CONVERSATION_TITLE_INVALID("32003", "会话标题不合法"),
    PLATFORM_CONVERSATION_SHARE_INVALID("32004", "分享对象不合法"),
    PLATFORM_CONVERSATION_ARCHIVED("32005", "会话已归档，请先恢复后再继续"),
    PLATFORM_CONVERSATION_DELETED("32006", "会话已删除"),
    PLATFORM_CONVERSATION_NOT_READY("32007", "平台管家尚未就绪，暂不能开始新对话"),
    // 33xxx 工作空间环境变量
    ENVIRONMENT_VARIABLE_NOT_FOUND("33001", "环境变量不存在"),
    ENVIRONMENT_VARIABLE_NAME_INVALID("33002", "环境变量名称不合法"),
    ENVIRONMENT_VARIABLE_NAME_RESERVED("33003", "环境变量名称为平台保留名称"),
    ENVIRONMENT_VARIABLE_NAME_CONFLICT("33004", "环境变量名称已存在"),
    ENVIRONMENT_VARIABLE_DELETE_IN_USE("33005", "环境变量被数字员工引用,无法删除"),
    ENVIRONMENT_VARIABLE_VERSION_CONFLICT("33006", "环境变量已被修改,请重试"),
    ENVIRONMENT_VARIABLE_VALUE_REQUIRED("33007", "环境变量值必须显式提供"),
    ENVIRONMENT_VARIABLE_REFERENCE_INVALID("33008", "数字员工版本引用的环境变量已不存在，请先移除引用"),
    // 34xxx 项目级能力分类
    CATEGORY_NOT_FOUND("34001", "分类不存在"),
    CATEGORY_NAME_REQUIRED("34002", "分类名称不能为空"),
    CATEGORY_DUPLICATE_NAME("34003", "同一父分类下已存在此名称"),
    CATEGORY_DELETE_IN_USE("34004", "分类下仍有子分类或能力关联，无法删除"),
    CATEGORY_CYCLE_MOVE("34005", "分类不能移动到自身或其子分类下"),
    CATEGORY_DEPTH_EXCEEDED("34006", "分类层级超过上限");


    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
}
