import { useEffect, useMemo, useState } from 'react';
import { Card, Table, Tag, Badge, Button, Space, Modal, Form, Input, InputNumber, Select, message, Popconfirm, Alert, Typography, Dropdown, Tooltip, Segmented, Collapse, Drawer, Descriptions } from 'antd';
import { PlusOutlined, DeleteOutlined, CopyOutlined, CheckCircleFilled, CodeOutlined, RobotOutlined, EyeOutlined, EyeInvisibleOutlined, CodeSandboxOutlined, DownOutlined, BugOutlined, TeamOutlined, CloudUploadOutlined, LinkOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listExecutors, createExecutor, deleteExecutor, getExecutorToken, getExecutorModelCatalog, restartExecutor,
  getExecutorLaunchConfig, updateExecutorLaunchConfig, buildExecutorLaunchCommand,
  updateExecutor, updateAllExecutors, getRuntimeAutoUpdate,
} from './api';
import { listAgents } from '@/features/agent/api';
import type { Agent } from '@/features/agent/api';
import { listSquadsWithMembers } from '@/features/squad/api';
import { SquadFilterBar } from '@/features/squad/SquadFilterBar';
import { SquadTags } from '@/features/squad/SquadTags';
import { buildSquadNameById, groupBySquad, type SquadGroup } from '@/features/squad/squadGrouping';
import { Link } from 'react-router-dom';
import { buildAgentSquadNameMap, formatAgentSquadLabel } from './agentSquadLabel';
import { buildExecutorAgentGroups, type ExecutorAgentGroup } from './executorAgentGroups';
import type { ExecutorVO, IssuedExecutorVO, ExecutorLaunchConfig, ExecutorUpdateVO, ExecutorUpdateAllResultVO } from './api';
import type { DebugShell, StartupOs } from './api';
import { ApiError } from '@/shared/types/common';
import type { ColumnsType } from 'antd/es/table';
import {
  chooseQoderModel,
  qoderOptionsForModel,
  qoderProviderForClientKind,
  resolveQoderModelOptions,
} from './qoderOptions';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { copyTextToClipboard } from '@/shared/lib/clipboard';
import { readViewPreference, writeViewPreference } from '@/shared/lib/viewPreference';
import { detectStartupOs } from './startupOs';

const CLIENT_KINDS: { value: string; label: string; color: string; Icon: typeof CodeOutlined }[] = [
  { value: 'QODER_CN_CLI', label: 'Qoder CLI CN', color: '#1677ff', Icon: CodeOutlined },
  { value: 'QODER_CLI', label: 'Qoder CLI', color: '#1677ff', Icon: CodeOutlined },
  { value: 'CLAUDE_CODE', label: 'Claude Code', color: '#d4380d', Icon: RobotOutlined },
  { value: 'CODEX_CLI', label: 'Codex CLI', color: '#13a8a8', Icon: CodeSandboxOutlined },
  { value: 'CURSOR_CLI', label: 'Cursor CLI', color: '#141414', Icon: CodeSandboxOutlined },
];

const clientKindMap = Object.fromEntries(CLIENT_KINDS.map((k) => [k.value, k]));

export function isQoderClientKind(kind?: string | null): boolean {
  return kind === 'QODER_CLI' || kind === 'QODER_CN_CLI';
}

// 新建执行器仅开放 Qoder 系执行器，其余类型在列表中仍正常展示
export const CREATABLE_CLIENT_KINDS = CLIENT_KINDS.filter((k) => isQoderClientKind(k.value));

const EXECUTORS_VIEW_STORAGE_KEY = 'autowonder.executors.view';
const EXECUTORS_VIEW_OPTIONS = ['agent', 'squad'] as const;

const statusBadge: Record<string, { status: 'success' | 'processing' | 'default'; text: string }> = {
  ONLINE: { status: 'success', text: '在线' },
  BUSY: { status: 'processing', text: '忙碌' },
  OFFLINE: { status: 'default', text: '离线' },
};

// 升级任务状态，与 ExecutorUpdateVO.status 一一对应，颜色沿用 antd 预设状态色。
const updateStatusLabels: Record<string, { text: string; color: string }> = {
  PENDING: { text: '待更新', color: 'default' },
  DRAINING: { text: '排空中', color: 'processing' },
  UPDATING: { text: '升级中', color: 'processing' },
  SUCCESS: { text: '升级成功', color: 'success' },
  FAILED: { text: '升级失败', color: 'error' },
};

const IN_PROGRESS_UPDATE_STATUSES = ['PENDING', 'DRAINING', 'UPDATING'];

export function isUpdateInProgress(update?: ExecutorUpdateVO | null): boolean {
  return Boolean(update && IN_PROGRESS_UPDATE_STATUSES.includes(update.status));
}

export function updateStatusLabel(status?: string | null): string {
  return (status && updateStatusLabels[status]?.text) || status || '';
}

export function updateStatusColor(status?: string | null): string {
  return (status && updateStatusLabels[status]?.color) || 'default';
}

/**
 * 一键更新的禁用原因，null 表示可以升级。只把按钮置灰而不说原因的话，操作者无从判断
 * 该做什么，所以每条拒绝路径都给出一句可执行的说明。三条分支与顺序都对应服务端
 * ExecutorUpdateService.updateOne 的校验，前端不另立一套规则。
 */
export function upgradeBlockReason(executor: ExecutorVO): string | null {
  // upgradeAvailable 为 false 有两种成因：版本不低于目标版本，或版本无法比较（从未上报，或
  // 0.2.155-beta.1、dev 这类非稳定版本）。只有前一种是「无需升级」——服务端 updateOne 也只在
  // 比较成功且 >= 0 时才拒绝，所以这里认服务端算好的 versionComparable，不再用 version 是否存在来猜。
  if (!executor.upgradeAvailable && executor.versionComparable) {
    return `已是目标版本 ${executor.targetVersion ?? executor.version}，无需升级`;
  }
  // 离线时 protocolFeatures（TTL 90s）早已过期，upgradeSupported 只代表「未知」而非「不支持」。
  // 服务端也只在在线时才校验能力：离线的任务先落库，等客户端重连再下发。
  if (executor.status === 'ONLINE' && !executor.upgradeSupported) {
    return '当前客户端不支持远程升级，请先在本地升级客户端';
  }
  if (isUpdateInProgress(executor.update)) return '已有升级任务进行中，请等待结果';
  return null;
}

/**
 * 批量升级结果的一句话总结。只报「成功 N 个」会让操作者漏掉被跳过的机器，所以跳过数
 * 必须出现在总结里，具体原因在结果弹窗中逐条展开。
 */
export function describeUpdateAllResult(result: ExecutorUpdateAllResultVO): string {
  const parts = [`目标版本 ${result.targetVersion}`, `已下发 ${result.scheduled} 个`];
  if (result.alreadyUpToDate > 0) parts.push(`${result.alreadyUpToDate} 个已是最新`);
  if (result.skipped.length > 0) parts.push(`${result.skipped.length} 个被跳过`);
  return parts.join('，');
}

const DEBUG_WARNING = '仅在需要排查问题时使用。debug 模式会持续写入完整日志，长期运行可能占满磁盘。排查结束后请改回普通启动命令。';

const DEBUG_SHELL_ITEMS: { key: DebugShell; label: string }[] = [
  { key: 'bash', label: 'Mac / Linux (bash)' },
  { key: 'powershell', label: 'Windows (PowerShell 7+)' },
];

const SHELL_LABEL: Record<DebugShell, string> = {
  bash: 'bash',
  powershell: 'PowerShell',
};

// 启动配置与命令生成完全由服务端负责，页面只把服务端给出的错误码翻译成可读文案
const LAUNCH_ERROR_MESSAGES: Record<string, string> = {
  '17001': '执行器不存在或已删除，无法生成启动命令',
  '17005': '配置已被修改，请刷新后重试',
  '17006': '当前模型已不可用，请重新选择',
  '17007': '启动配置不完整，请先保存启动配置后再生成命令',
  '17008': '启动参数不支持临时覆盖，请先保存启动配置',
  '17011': '该执行器缺少客户端类型，无法生成启动命令',
};

// 网络中断等系统级失败被拦截器统一包成该 code，message 是英文技术文案，对操作者没有可读信息
const SYSTEM_ERROR_CODE = '10000';

export function launchErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const mapped = LAUNCH_ERROR_MESSAGES[error.code];
    if (mapped) return mapped;
    if (error.code === SYSTEM_ERROR_CODE) return fallback;
    return error.message || fallback;
  }
  return fallback;
}

// 从未配置过的执行器四项全为 null，此时页面展示「未配置」并禁止生成命令，绝不用默认值兜底
export function isLaunchConfigured(config: ExecutorLaunchConfig | null): boolean {
  if (!config) return false;
  return Boolean(config.model || config.reasoningEffort || config.contextWindow || config.memoryMode);
}

const UNCONFIGURED_PREVIEW = '未配置：请先选择启动参数并保存后再生成命令';

function CopyCommandActions({ onCopy, disabled }: { onCopy: (shell?: DebugShell) => void; disabled?: boolean }) {
  return (
    <Space>
      <Button type="primary" disabled={disabled} onClick={() => onCopy()}>复制启动命令</Button>
      <Tooltip title={DEBUG_WARNING}>
        <Dropdown
          disabled={disabled}
          trigger={['click']}
          menu={{ items: DEBUG_SHELL_ITEMS, onClick: ({ key }) => onCopy(key as DebugShell) }}
        >
          <Button disabled={disabled} icon={<BugOutlined />}>复制 debug 模式命令 <DownOutlined /></Button>
        </Dropdown>
      </Tooltip>
    </Space>
  );
}

function OsSegmented({ value, onChange }: { value: StartupOs; onChange: (os: StartupOs) => void }) {
  return (
    <Segmented
      size="small"
      value={value}
      onChange={(os) => onChange(os as StartupOs)}
      options={[
        { value: 'windows', label: 'Windows' },
        { value: 'posix', label: 'macOS / Linux' },
      ]}
      style={{ display: 'block', marginTop: 8 }}
    />
  );
}

function CommandPreview({ text }: { text: string }) {
  return (
    <div style={{
      marginTop: 4, padding: '8px 12px',
      background: '#f5f5f5', borderRadius: 6, fontFamily: 'monospace', fontSize: 13,
      wordBreak: 'break-all', lineHeight: 1.6,
    }}>
      {text}
    </div>
  );
}

function ClientKindSelect({ value, onChange }: { value?: string; onChange?: (v: string) => void }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
      {CREATABLE_CLIENT_KINDS.map(({ value: v, label, color, Icon }) => {
        const selected = value === v;
        return (
          <div
            key={v}
            onClick={() => onChange?.(v)}
            style={{
              flex: '1 1 150px',
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              padding: '12px 16px',
              border: `2px solid ${selected ? '#1677ff' : '#d9d9d9'}`,
              borderRadius: 8,
              cursor: 'pointer',
              background: selected ? '#f0f5ff' : '#fff',
              transition: 'all 0.2s',
              position: 'relative',
            }}
          >
            <Icon style={{ fontSize: 24, color }} />
            <span style={{ fontWeight: 500 }}>{label}</span>
            {selected && (
              <CheckCircleFilled
                style={{ position: 'absolute', top: 8, right: 8, color: '#1677ff', fontSize: 16 }}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}

function ExecutorGroupHeader({ group }: { group: ExecutorAgentGroup }) {
  return (
    <Space size={8} wrap>
      <RobotOutlined style={{ color: '#1677ff' }} />
      <Typography.Text strong>{group.label}</Typography.Text>
      <Tag>{`${group.executors.length} 个执行器`}</Tag>
      <Badge status="success" text={`在线 ${group.statusSummary.online}`} />
      <Badge status="processing" text={`忙碌 ${group.statusSummary.busy}`} />
      <Badge status="default" text={`离线 ${group.statusSummary.offline}`} />
    </Space>
  );
}

function ExecutorSquadGroupHeader({ group }: { group: SquadGroup<ExecutorVO> }) {
  return (
    <Space size={8} wrap>
      <TeamOutlined style={{ color: '#1677ff' }} />
      <Typography.Text strong>
        {group.label}
      </Typography.Text>
      {group.squadId != null && (
        <Tooltip title="查看小队">
          <Link to={`/squads?squadId=${group.squadId}`} aria-label={`查看小队：${group.label}`}
            onClick={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}>
            <LinkOutlined />
          </Link>
        </Tooltip>
      )}
      <Tag>{`${group.items.length} 个执行器`}</Tag>
    </Space>
  );
}

interface CreateExecutorFormValues {
  agentId: number;
  name: string;
  clientKind: string;
  memoryMode: string;
  maxConcurrentDispatches: number;
  model?: string;
  reasoningEffort?: string;
  contextWindow?: string;
}

interface StartupFormValues {
  memoryMode: string;
  maxConcurrentDispatches: number;
  model?: string;
  reasoningEffort?: string;
  contextWindow?: string;
}

export function ExecutorListPage() {
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [selectedAgentId, setSelectedAgentId] = useState<number | undefined>();
  // 只记录用户主动展开的分组，Agent 分组默认折叠，异步加载或刷新后新出现的分组同样保持折叠。
  const [expandedAgentKeys, setExpandedAgentKeys] = useState<string[]>([]);
  const [squadFilter, setSquadFilter] = useState<number[]>([]);
  const [squadGrouped, setSquadGrouped] = useState(
    () => readViewPreference(EXECUTORS_VIEW_STORAGE_KEY, EXECUTORS_VIEW_OPTIONS, 'squad') === 'squad',
  );
  const [expandedSquadKeys, setExpandedSquadKeys] = useState<string[]>([]);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [tokenResult, setTokenResult] = useState<IssuedExecutorVO | null>(null);
  const [form] = Form.useForm();
  const [startupForm] = Form.useForm();
  const [startupTarget, setStartupTarget] = useState<ExecutorVO | null>(null);
  const [revealedTokens, setRevealedTokens] = useState<Record<number, string>>({});
  const [loadingTokenId, setLoadingTokenId] = useState<number | null>(null);
  const [deleteConfirmId, setDeleteConfirmId] = useState<number | null>(null);
  const [startupOs, setStartupOs] = useState<StartupOs>(detectStartupOs());
  // 服务端读回的启动配置，同时携带下次保存必须回传的乐观锁版本号；读取失败时为 null
  const [startupConfig, setStartupConfig] = useState<ExecutorLaunchConfig | null>(null);
  const [startupConfigError, setStartupConfigError] = useState<string | null>(null);
  const [startupSaving, setStartupSaving] = useState(false);
  // 服务端返回的 Qoder 配置里模型已被清空（不再提供），提示用户重新选择
  const [startupModelInvalid, setStartupModelInvalid] = useState(false);
  // 一键全量更新的确认弹窗开关，以及下发完成后逐条展示跳过原因的结果。
  const [updateAllOpen, setUpdateAllOpen] = useState(false);
  const [updateAllResult, setUpdateAllResult] = useState<ExecutorUpdateAllResultVO | null>(null);
  const clientKind = Form.useWatch('clientKind', form);
  const qoderModel = Form.useWatch('model', form) ?? 'auto';
  const startupQoderModel = Form.useWatch('model', startupForm) ?? 'auto';
  const createQoderProvider = createModalOpen ? qoderProviderForClientKind(clientKind) : undefined;
  const startupQoderProvider = qoderProviderForClientKind(startupTarget?.clientKind);

  const qoderCatalogQuery = useQuery({
    queryKey: ['executor-model-catalog', 'qoder'],
    queryFn: () => getExecutorModelCatalog('qoder'),
    enabled: createQoderProvider === 'qoder' || startupQoderProvider === 'qoder',
    retry: false,
  });
  const qoderCnCatalogQuery = useQuery({
    queryKey: ['executor-model-catalog', 'qodercn'],
    queryFn: () => getExecutorModelCatalog('qodercn'),
    enabled: createQoderProvider === 'qodercn' || startupQoderProvider === 'qodercn',
    retry: false,
  });
  const qoderCatalogModels = qoderCatalogQuery.isError ? undefined : qoderCatalogQuery.data?.models;
  const qoderCnCatalogModels = qoderCnCatalogQuery.isError ? undefined : qoderCnCatalogQuery.data?.models;
  const createQoderModelOptions = useMemo(() => resolveQoderModelOptions(
    createQoderProvider === 'qoder'
      ? qoderCatalogModels
      : createQoderProvider === 'qodercn'
        ? qoderCnCatalogModels
        : undefined,
  ), [createQoderProvider, qoderCatalogModels, qoderCnCatalogModels]);
  const startupQoderModelOptions = useMemo(() => resolveQoderModelOptions(
    startupQoderProvider === 'qoder'
      ? qoderCatalogModels
      : startupQoderProvider === 'qodercn'
        ? qoderCnCatalogModels
        : undefined,
  ), [startupQoderProvider, qoderCatalogModels, qoderCnCatalogModels]);

  // 创建弹窗的模型下拉依赖服务端目录，目录变化时把默认值补齐，保证提交的四个启动参数完整。
  useEffect(() => {
    if (!createQoderProvider) return;
    const model = chooseQoderModel(createQoderModelOptions, form.getFieldValue('model'));
    if (!model || model === form.getFieldValue('model')) return;
    const options = qoderOptionsForModel(model);
    form.setFieldsValue({
      model,
      reasoningEffort: options.defaultReasoningEffort,
      contextWindow: options.defaultContextWindow,
    });
  }, [createQoderModelOptions, createQoderProvider, form]);

  const { data: agents = [] } = useQuery({
    queryKey: ['agents', 1, 100],
    queryFn: () => listAgents({ page: 1, size: 100 }),
  });

  const squadsQuery = useQuery({
    queryKey: ['squads', 'executor-agent-options'],
    queryFn: () => listSquadsWithMembers(),
  });
  const squadNameMap = useMemo(
    () => buildAgentSquadNameMap(squadsQuery.data ?? []),
    [squadsQuery.data],
  );
  const agentOptionLabel = (agent: Agent) => squadsQuery.data
    ? formatAgentSquadLabel(agent.name, squadNameMap.get(agent.id))
    : agent.name;
  const squadOptions = useMemo(
    () => (squadsQuery.data ?? []).map((squad) => ({ value: squad.id, label: squad.name })),
    [squadsQuery.data],
  );
  const squadNameById = useMemo(() => buildSquadNameById(squadOptions), [squadOptions]);

  const [detailId, setDetailId] = useState<number | null>(null);
  const restartMut = useMutation({
    mutationFn: ({ id, update }: { id: number; update: boolean }) => restartExecutor(id, update),
    onSuccess: (result) => { if (result?.status === 'FAILED') message.error(result.message); else message.success('已发送请求，等待客户端重启结果'); queryClient.invalidateQueries({ queryKey: ['executors'] }); },
    onError: (error) => message.error(launchErrorMessage(error, '重启请求失败')),
  });
  // 自动升级开关对所有登录用户可读。开关本身是服务端 application.yml 里的部署配置（默认开启），
  // 面板只展示当前状态，没有写入口。
  const autoUpdateQuery = useQuery({
    queryKey: ['platform-runtime-auto-update'],
    queryFn: getRuntimeAutoUpdate,
  });
  const updateMut = useMutation({
    mutationFn: (id: number) => updateExecutor(id),
    onSuccess: (result) => {
      message.success(`已下发升级指令，目标版本 ${result.targetVersion}`);
      queryClient.invalidateQueries({ queryKey: ['executors'] });
    },
    onError: (error) => message.error(launchErrorMessage(error, '升级请求失败')),
  });
  const updateAllMut = useMutation({
    mutationFn: () => updateAllExecutors(squadFilter),
    onSuccess: (result) => {
      setUpdateAllOpen(false);
      setUpdateAllResult(result);
      queryClient.invalidateQueries({ queryKey: ['executors'] });
    },
    onError: (error) => message.error(launchErrorMessage(error, '批量升级请求失败')),
  });
  const { data: executors = [], isLoading } = useQuery({
    queryKey: ['executors', selectedAgentId, squadFilter],
    queryFn: () => listExecutors(selectedAgentId, squadFilter),
    refetchInterval: detailId === null ? 10000 : 3000,
  });

  const visibleExecutors = useMemo(() => {
    if (squadFilter.length === 0) {
      return executors;
    }
    // GET /api/executors already filtered these server-side; the per-agent endpoint did not.
    return executors.filter((executor) => (executor.squadIds ?? [])
      .some((squadId) => squadFilter.includes(squadId)));
  }, [executors, squadFilter]);

  // 确认弹窗要说清这次会动多少台。这里复用 upgradeBlockReason 而不是只看 upgradeAvailable：
  // 服务端 updateAll 会跳过「已是目标版本」「已有任务进行中」「在线但不支持」三类，也会受理版本
  // 无法解析的执行器，只有同一套判定才能让弹窗里的数量和逐行按钮的置灰状态一致。
  const upgradableExecutors = useMemo(
    () => visibleExecutors.filter((executor) => upgradeBlockReason(executor) === null),
    [visibleExecutors],
  );

  const agentNameById = useMemo(
    () => new Map(agents.map((agent) => [agent.id, agent.name])),
    [agents],
  );

  const executorGroups = useMemo(() => buildExecutorAgentGroups(
    visibleExecutors,
    (agentId, agentName) => {
      const name = agentName ?? agentNameById.get(agentId);
      if (!name) return null;
      return squadsQuery.data
        ? formatAgentSquadLabel(name, squadNameMap.get(agentId))
        : name;
    },
  ), [agentNameById, visibleExecutors, squadNameMap, squadsQuery.data]);

  const squadGroups = useMemo(
    () => groupBySquad(visibleExecutors, (executor) => executor.squadIds, squadNameById),
    [visibleExecutors, squadNameById],
  );

  const handleAgentCollapseChange = (keys: string | string[]) => {
    // 非手风琴模式下 antd 恒传数组，用 flat 归一化两种签名，避免留下不可达分支。
    setExpandedAgentKeys([keys].flat());
  };

  const handleSquadCollapseChange = (keys: string | string[]) => {
    setExpandedSquadKeys([keys].flat());
  };

  const startupConfigured = isLaunchConfigured(startupConfig);

  // 命令预览与复制共用同一条服务端生成结果：页面与 MCP 拿到的是完全相同的启动参数。
  const startupCommandQuery = useQuery({
    queryKey: ['executor-launch-command', startupTarget?.id, startupOs],
    queryFn: () => buildExecutorLaunchCommand(startupTarget!.id, { os: startupOs }),
    enabled: Boolean(startupTarget) && startupConfigured && !startupConfigError,
    retry: false,
  });
  const issuedCommandQuery = useQuery({
    queryKey: ['executor-launch-command', tokenResult?.id, startupOs],
    queryFn: () => buildExecutorLaunchCommand(tokenResult!.id, { os: startupOs }),
    enabled: Boolean(tokenResult),
    retry: false,
  });

  const createMut = useMutation({
    // 启动配置随执行器一并落库，响应回显的就是数据库保存下来的值
    mutationFn: ({ agentId, name, clientKind: kind, memoryMode, maxConcurrentDispatches, model, reasoningEffort, contextWindow }:
      CreateExecutorFormValues) =>
      createExecutor(agentId, { name, clientKind: kind, memoryMode, maxConcurrentDispatches, model, reasoningEffort, contextWindow }),
    onSuccess: (data) => {
      setTokenResult(data);
      setCreateModalOpen(false);
      form.resetFields();
      queryClient.invalidateQueries({ queryKey: ['executors'] });
    },
  });

  const deleteMut = useMutation({
    mutationFn: deleteExecutor,
    onSuccess: () => {
      message.success('执行器已删除');
      queryClient.invalidateQueries({ queryKey: ['executors'] });
    },
  });

  const handleCreate = async () => {
    runAccessCommand('ADMIN', '新建执行器', async () => {
      const values = await form.validateFields();
      createMut.mutate(values);
    });
  };

  const startupQoderOptions = qoderOptionsForModel(startupQoderModel);
  const qoderModelOptions = qoderOptionsForModel(qoderModel);

  let startupPreview = '';
  if (startupTarget) {
    if (startupConfigError) {
      startupPreview = startupConfigError;
    } else if (!startupConfig) {
      startupPreview = '启动配置读取中…';
    } else if (!startupConfigured) {
      startupPreview = UNCONFIGURED_PREVIEW;
    } else if (startupCommandQuery.isError) {
      startupPreview = launchErrorMessage(startupCommandQuery.error, '启动命令生成失败');
    } else {
      startupPreview = startupCommandQuery.data?.command ?? '启动命令生成中…';
    }
  }

  const issuedPreview = issuedCommandQuery.isError
    ? launchErrorMessage(issuedCommandQuery.error, '启动命令生成失败')
    : issuedCommandQuery.data?.command ?? '启动命令生成中…';

  const handleCopyToken = () => {
    runAccessCommand('ADMIN', '复制执行器 Token', async () => {
      if (tokenResult?.token) {
        const copied = await copyTextToClipboard(tokenResult.token);
        if (copied) {
          message.success('Token 已复制到剪贴板');
        } else {
          message.warning('自动复制失败，请手动复制');
        }
      }
    });
  };

  const handleRevealToken = (id: number) => {
    runAccessCommand('ADMIN', '查看执行器 Token', async () => {
      if (revealedTokens[id]) {
        setRevealedTokens((prev) => { const next = { ...prev }; delete next[id]; return next; });
        return;
      }
      setLoadingTokenId(id);
      try {
        const token = await getExecutorToken(id);
        setRevealedTokens((prev) => ({ ...prev, [id]: token }));
      } catch {
        message.error('Token 不可回显，请重新创建执行器');
      } finally {
        setLoadingTokenId(null);
      }
    });
  };

  const handleCopyRowToken = (id: number) => {
    runAccessCommand('ADMIN', '复制执行器 Token', async () => {
      let token = revealedTokens[id];
      if (!token) {
        try {
          token = await getExecutorToken(id);
          setRevealedTokens((prev) => ({ ...prev, [id]: token }));
        } catch {
          message.error('Token 不可回显');
          return;
        }
      }
      const copied = await copyTextToClipboard(token);
      if (copied) {
        message.success('Token 已复制');
      } else {
        message.warning('自动复制失败，请手动复制');
      }
    });
  };

  // 生成与复制都只带输出格式选项，任何启动值都由服务端从数据库读取后拼进命令
  const copyLaunchCommand = async (executorId: number, shell?: DebugShell): Promise<boolean> => {
    let command: string;
    try {
      const vo = await buildExecutorLaunchCommand(
        executorId,
        shell ? { debug: true, shell } : { os: startupOs },
      );
      command = vo.command;
    } catch (error) {
      message.error(launchErrorMessage(error, '启动命令生成失败'));
      return false;
    }
    const copied = await copyTextToClipboard(command);
    if (!copied) {
      message.warning('自动复制失败，请手动复制');
      return false;
    }
    if (shell) {
      message.warning(`debug 命令已复制（${SHELL_LABEL[shell]}）——仅用于排查问题，排查完请改回普通启动命令，避免日志写满磁盘`, 6);
    } else {
      message.success('启动命令已复制');
    }
    return true;
  };

  const loadStartupConfig = async (record: ExecutorVO, fillForm: boolean) => {
    let config: ExecutorLaunchConfig;
    try {
      config = await getExecutorLaunchConfig(record.id);
    } catch (error) {
      // 读不到数据库配置就不能生成命令，如实告知原因并阻止复制（禁止回退默认值）
      setStartupConfig(null);
      setStartupConfigError(launchErrorMessage(error, '启动配置读取失败，无法生成命令'));
      return null;
    }
    setStartupConfigError(null);
    setStartupConfig(config);
    if (!fillForm || !isLaunchConfigured(config)) return config;
    if (isQoderClientKind(record.clientKind)) {
      startupForm.setFieldsValue({
        memoryMode: config.memoryMode ?? 'platform',
        maxConcurrentDispatches: config.maxConcurrentDispatches ?? 5,
        model: config.model ?? undefined,
        reasoningEffort: config.reasoningEffort ?? undefined,
        contextWindow: config.contextWindow ?? undefined,
      });
      setStartupModelInvalid(!config.model);
    } else {
      startupForm.setFieldsValue({ memoryMode: config.memoryMode ?? 'platform', maxConcurrentDispatches: config.maxConcurrentDispatches ?? 5 });
    }
    return config;
  };

  const openStartupModal = (record: ExecutorVO) => {
    runAccessCommand('ADMIN', '查看执行器启动命令', async () => {
      startupForm.resetFields();
      setStartupTarget(record);
      setStartupConfig(null);
      setStartupConfigError(null);
      setStartupModelInvalid(false);
      await loadStartupConfig(record, true);
    });
  };

  const persistStartupConfig = async (record: ExecutorVO): Promise<boolean> => {
    let values: StartupFormValues;
    try {
      values = await startupForm.validateFields();
    } catch {
      // 表单本身不完整（例如模型已不可用被置空），此时既不能保存也不能生成命令
      return false;
    }
    let config = startupConfig;
    if (!config) {
      // 打开弹窗时读取失败过：保存前必须补读到配置与版本号，绝不猜测版本
      config = await loadStartupConfig(record, false);
    }
    if (!config) {
      message.error('启动配置读取失败，无法保存');
      return false;
    }
    const version = config.version;
    const isQoder = isQoderClientKind(record.clientKind);
    setStartupSaving(true);
    try {
      const saved = await updateExecutorLaunchConfig(record.id, isQoder
        ? {
            memoryMode: values.memoryMode,
            maxConcurrentDispatches: values.maxConcurrentDispatches,
            model: values.model,
            reasoningEffort: values.reasoningEffort,
            contextWindow: values.contextWindow,
            version,
          }
        : { memoryMode: values.memoryMode, maxConcurrentDispatches: values.maxConcurrentDispatches, version });
      setStartupConfig(saved);
      setStartupModelInvalid(false);
      // 保存成功后预览必须重新向服务端索取，确保展示的就是数据库里的配置
      await queryClient.invalidateQueries({ queryKey: ['executor-launch-command', record.id] });
      return true;
    } catch (error) {
      if (error instanceof ApiError && error.code === '17005') {
        message.error('配置已被修改，请刷新后重试');
        // 同步最新配置与版本号，用户可以直接重试；表单里刚选的值保持不动
        await loadStartupConfig(record, false);
      } else if (error instanceof ApiError && error.code === '17006') {
        // 选择的模型已下线：置空并提示重新选择，不自动回退默认值
        setStartupModelInvalid(true);
        startupForm.setFieldsValue({ model: undefined });
        message.error('当前模型已不可用，请重新选择');
      } else {
        message.error(launchErrorMessage(error, '启动配置保存失败'));
      }
      return false;
    } finally {
      setStartupSaving(false);
    }
  };

  const saveFromStartupModal = () => {
    runAccessCommand('ADMIN', '保存执行器启动配置', async () => {
      if (!startupTarget) return;
      if (await persistStartupConfig(startupTarget)) {
        message.success('启动配置已保存，请使用新启动命令重启执行器使配置生效');
      }
    });
  };

  const copyFromStartupModal = (shell?: DebugShell) => {
    runAccessCommand('ADMIN', shell ? '复制执行器 debug 启动命令' : '复制执行器启动命令', async () => {
      if (!startupTarget) return;
      // 先把当前选择写回服务端，再让服务端按数据库配置生成命令，页面与 MCP 共享同一份配置
      if (!(await persistStartupConfig(startupTarget))) return;
      if (await copyLaunchCommand(startupTarget.id, shell)) {
        setStartupTarget(null);
      }
    });
  };

  const copyFromTokenModal = (shell?: DebugShell) => {
    runAccessCommand('ADMIN', shell ? '复制执行器 debug 启动命令' : '复制执行器启动命令', async () => {
      if (!tokenResult) return;
      await copyLaunchCommand(tokenResult.id, shell);
    });
  };

  const detail = executors.find((executor) => executor.id === detailId);
  const restartPending = Boolean(detail?.restart && ['REQUESTED', 'UPDATING', 'RESTARTING'].includes(detail.restart.status));
  const formatTime = (value?: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '未上报';
  const restartLabels: Record<string, string> = { REQUESTED: '等待响应', UPDATING: '准备更新', RESTARTING: '正在重启', COMPLETED: '重启成功', FAILED: '重启失败', TIMED_OUT: '等待超时' };
  const columns: ColumnsType<ExecutorVO> = [
    { title: '执行器', dataIndex: 'name', width: 180, render: (name, record) => (
      <div><Button type="link" style={{ padding: 0, maxWidth: '100%' }} onClick={() => setDetailId(record.id)}>{name}</Button>
        <div style={{ color: '#8c8c8c', fontSize: 12 }}>ID {record.id}</div></div>
    ) },
    { title: '归属', width: 180, render: (_, record) => (
      <div><div>{record.agentName ?? '-'}</div><SquadTags squadIds={record.squadIds} squadNames={record.squadNames} /></div>
    ) },
    { title: '客户端 / 版本', width: 150, render: (_, record) => (
      // 历史数据的 clientKind 可为 null：明确标记「类型缺失」，绝不留空白让操作者误判客户端类型。
      <div><div>{record.clientKind
        ? (clientKindMap[record.clientKind]?.label ?? record.clientKind)
        : <Tag color="warning">类型缺失</Tag>}</div>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{record.version ?? '版本未上报'}</Typography.Text>
        <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>{record.modelName ?? record.model ?? '模型未上报'}</Typography.Text></div>
        {record.upgradeAvailable && <div style={{ marginTop: 2 }}><Tag color="orange">有新版本</Tag></div>}</div>
    ) },
    { title: '状态', dataIndex: 'status', width: 120, render: (status: string, record) => (
      <div><Badge {...(statusBadge[status] ?? { status: 'default' as const, text: status })} />
        {record.restart && <div style={{ fontSize: 12 }}>{restartLabels[record.restart.status] ?? record.restart.status}</div>}
        {record.update && <div style={{ marginTop: 2 }}>
          <Tooltip title={record.update.lastError ?? undefined}>
            <Tag color={updateStatusColor(record.update.status)}>{updateStatusLabel(record.update.status)}</Tag>
          </Tooltip>
        </div>}</div>
    ) },
    { title: '最近启动', dataIndex: 'lastStartedAt', width: 170, render: (value: string | null) => formatTime(value) },
    {
      title: '操作', width: 300, fixed: 'right',
      render: (_: unknown, record: ExecutorVO) => {
        // 拒绝升级时只置灰按钮的话操作者不知道该做什么，所以把原因放在 Tooltip 里。
        const blockReason = upgradeBlockReason(record);
        return (
          <Space size={0}>
            <Button type="link" size="small" onClick={() => setDetailId(record.id)}>详情</Button>
            <Button type="link" size="small" icon={<CodeSandboxOutlined />}
              onClick={() => openStartupModal(record)}>启动命令</Button>
            {/* 禁用状态的 button 不触发鼠标事件，包一层 span 才能让 Tooltip 浮出拒绝原因。 */}
            <Tooltip title={blockReason ?? undefined}>
              <span>
                <Button type="link" size="small" icon={<CloudUploadOutlined />} disabled={blockReason !== null}
                  loading={updateMut.isPending && updateMut.variables === record.id}
                  onClick={() => runAccessCommand('ADMIN', '升级执行器', () => updateMut.mutate(record.id))}>一键更新</Button>
              </span>
            </Tooltip>
            <Popconfirm
              title="确认删除此执行器？"
              open={deleteConfirmId === record.id}
              onOpenChange={(open) => {
                if (!open) {
                  setDeleteConfirmId(null);
                  return;
                }
                runAccessCommand('ADMIN', '删除执行器', () => setDeleteConfirmId(record.id));
              }}
              onConfirm={() => {
                setDeleteConfirmId(null);
                runAccessCommand('ADMIN', '删除执行器', () => deleteMut.mutate(record.id));
              }}
            >
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <div>
      <Drawer title={detail ? `${detail.name} · 执行器详情` : '执行器详情'} open={detailId !== null}
        width={520} onClose={() => { if (detailId !== null) setRevealedTokens((prev) => { const next = { ...prev }; delete next[detailId]; return next; }); setDetailId(null); }}>
        {detail && <Space direction="vertical" size={24} style={{ width: '100%' }}>
          <Descriptions column={1} size="small" title="运行信息">
            <Descriptions.Item label="执行器 ID">{detail.id}</Descriptions.Item>
            <Descriptions.Item label="数字员工">{detail.agentName ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="当前版本">{detail.version ?? '未上报'}</Descriptions.Item>
            <Descriptions.Item label="当前模型">{detail.modelName ?? detail.model ?? '未上报'}</Descriptions.Item>
            <Descriptions.Item label="最近启动时间">{formatTime(detail.lastStartedAt)}</Descriptions.Item>
            <Descriptions.Item label="最后心跳">{formatTime(detail.lastHeartbeat)}</Descriptions.Item>
            <Descriptions.Item label="接入 IP">{detail.lastConnectIp ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="创建时间">{formatTime(detail.gmtCreate)}</Descriptions.Item>
          </Descriptions>
          <div>
            <Typography.Title level={5}>客户端维护</Typography.Title>
            <Typography.Paragraph type="secondary">空闲时执行重启；有任务运行时会返回忙碌，不会强制中断。更新并重启会检查最新发布版，本地源码版仅支持重启已编译程序。</Typography.Paragraph>
            <Space wrap>
              <Button disabled={detail.status !== 'ONLINE' || !detail.restartSupported || restartPending} loading={restartMut.isPending}
                onClick={() => runAccessCommand('ADMIN', '重启执行器', () => restartMut.mutate({ id: detail.id, update: false }))}>重启客户端</Button>
              <Button type="primary" disabled={detail.status !== 'ONLINE' || !detail.updateRestartSupported || restartPending} loading={restartMut.isPending}
                onClick={() => runAccessCommand('ADMIN', '更新并重启执行器', () => restartMut.mutate({ id: detail.id, update: true }))}>更新并重启</Button>
            </Space>
            {!detail.restartSupported && <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>此客户端尚未上报远程重启能力，请先在本地升级至支持版本。</Typography.Paragraph>}
            {detail.restart && <Alert style={{ marginTop: 16 }} showIcon
              type={detail.restart.status === 'COMPLETED' ? 'success' : ['FAILED', 'TIMED_OUT'].includes(detail.restart.status) ? 'error' : 'info'}
              message={restartLabels[detail.restart.status] ?? detail.restart.status} description={detail.restart.message} />}
          </div>
          <div>
            <Typography.Title level={5}>连接凭据</Typography.Title>
            <Space wrap>
              <Typography.Text code style={{ wordBreak: 'break-all' }}>{revealedTokens[detail.id] ?? '••••••••••••'}</Typography.Text>
              <Button size="small" loading={loadingTokenId === detail.id} icon={revealedTokens[detail.id] ? <EyeInvisibleOutlined /> : <EyeOutlined />}
                onClick={() => handleRevealToken(detail.id)}>{revealedTokens[detail.id] ? '隐藏' : '显示'}</Button>
              <Button size="small" icon={<CopyOutlined />} onClick={() => handleCopyRowToken(detail.id)}>复制 Token</Button>
            </Space>
          </div>
        </Space>}
      </Drawer>
      <Card title="执行器管理"
        extra={
          <Space wrap>
            <SquadFilterBar
              options={squadOptions}
              value={squadFilter}
              onChange={setSquadFilter}
              grouped={squadGrouped}
              onGroupedChange={(next) => {
                setSquadGrouped(next);
                writeViewPreference(EXECUTORS_VIEW_STORAGE_KEY, next ? 'squad' : 'agent');
              }}
              loading={squadsQuery.isLoading}
              listLabel="按 Agent 分组"
              groupLabel="按小队分组"
            />
            <Select placeholder="选择 Agent" style={{ width: 200 }} value={selectedAgentId}
              onChange={setSelectedAgentId} allowClear showSearch optionFilterProp="label"
              options={agents.map(a => ({ value: a.id, label: a.name }))}
            />
            {/* Tooltip 要靠子元素的 ref 定位，Space 是组件不是 DOM 节点，包一层 span 最稳妥。 */}
            <Tooltip title="开启后，服务端发现执行器版本落后于目标版本时，会在派单间隙自动下发升级指令；开关由服务端 application.yml 配置（默认开启）">
              <span>
                <Space size={4}>
                  <Typography.Text type="secondary">自动升级</Typography.Text>
                  {/* 兜底取服务端的默认值「开启」：配置还没回来或这次读取失败时，显示「关闭」会让
                      操作者以为自动升级被谁关掉了，而服务端此刻仍在自动下发。 */}
                  {autoUpdateQuery.data?.executorAutoUpdateEnabled ?? true
                    ? <Tag color="success">已开启</Tag>
                    : <Tag>已关闭</Tag>}
                </Space>
              </span>
            </Tooltip>
            <Tooltip title={upgradableExecutors.length === 0 ? '当前筛选范围内没有可升级的执行器' : undefined}>
              <span>
                <Button icon={<CloudUploadOutlined />} disabled={upgradableExecutors.length === 0}
                  loading={updateAllMut.isPending}
                  onClick={() => runAccessCommand('ADMIN', '批量升级执行器', () => setUpdateAllOpen(true))}>一键全量更新</Button>
              </span>
            </Tooltip>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => {
              runAccessCommand('ADMIN', '新建执行器', () => {
                form.resetFields();
                const defaults = qoderOptionsForModel('auto');
                form.setFieldsValue({
                  agentId: selectedAgentId,
                  clientKind: 'QODER_CLI',
                  memoryMode: 'platform',
                  model: 'auto',
                  reasoningEffort: defaults.defaultReasoningEffort,
                  contextWindow: defaults.defaultContextWindow,
                });
                setCreateModalOpen(true);
              });
            }}>
              新建执行器
            </Button>
          </Space>
        }
      >
        {squadGrouped ? (
          squadGroups.length > 0 ? (
            <Collapse
              activeKey={expandedSquadKeys}
              onChange={handleSquadCollapseChange}
              items={squadGroups.map((group) => ({
                key: group.key,
                label: <ExecutorSquadGroupHeader group={group} />,
                children: (
                  <Table
                    rowKey="id"
                    columns={columns}
                    dataSource={group.items}
                    pagination={false}
                    scroll={{ x: 1100 }}
                  />
                ),
              }))}
            />
          ) : (
            <Table
              rowKey="id"
              columns={columns}
              dataSource={visibleExecutors}
              loading={isLoading}
              pagination={false}
              scroll={{ x: 1100 }}
            />
          )
        ) : executorGroups.length > 0 ? (
          <Collapse
            activeKey={expandedAgentKeys}
            onChange={handleAgentCollapseChange}
            items={executorGroups.map((group) => ({
              key: group.key,
              label: <ExecutorGroupHeader group={group} />,
              children: (
                <Table
                  rowKey="id"
                  columns={columns}
                  dataSource={group.executors}
                  pagination={false}
                  scroll={{ x: 1100 }}
                />
              ),
            }))}
          />
        ) : (
          <Table
            rowKey="id"
            columns={columns}
            dataSource={visibleExecutors}
            loading={isLoading}
            pagination={false}
            scroll={{ x: 1100 }}
          />
        )}
      </Card>

      {/* Create Modal */}
      <Modal title="新建执行器" open={createModalOpen}
        onOk={handleCreate} onCancel={() => setCreateModalOpen(false)}
        confirmLoading={createMut.isPending}>
        <Form form={form} layout="vertical" initialValues={{
          clientKind: 'QODER_CLI', memoryMode: 'platform', maxConcurrentDispatches: 5, model: 'auto',
          reasoningEffort: qoderOptionsForModel('auto').defaultReasoningEffort,
          contextWindow: qoderOptionsForModel('auto').defaultContextWindow,
        }}>
          <Form.Item label="归属 Agent" name="agentId" rules={[{ required: true, message: '请选择归属 Agent' }]}>
            <Select placeholder="选择 Agent" showSearch optionFilterProp="label"
              options={agents.map(a => ({ value: a.id, label: agentOptionLabel(a) }))}
            />
          </Form.Item>
          <Form.Item label="客户端类型" name="clientKind" rules={[{ required: true, message: '请选择类型' }]}>
            <ClientKindSelect />
          </Form.Item>
          <Form.Item label="最大并发任务数" name="maxConcurrentDispatches"
            rules={[{ required: true, type: 'integer', min: 1, max: 10, message: '请输入 1 到 10 的整数' }]}
            extra={<span style={{ whiteSpace: 'nowrap' }}>默认 5，范围 1–10，重启生效。</span>}>
            <InputNumber min={1} max={10} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="记忆模式" name="memoryMode" rules={[{ required: true, message: '请选择记忆模式' }]}>
            <Select options={[
              { value: 'platform', label: '平台记忆（推荐）' },
              { value: 'provider-local', label: '本机 Agent 记忆' },
              { value: 'none', label: '关闭记忆' },
            ]} />
          </Form.Item>
          {isQoderClientKind(clientKind) && (
            <>
              <Form.Item label="Qoder 模型" name="model" rules={[{ required: true, message: '请选择 Qoder 模型' }]}>
                <Select options={createQoderModelOptions} onChange={(model) => {
                  const options = qoderOptionsForModel(model);
                  form.setFieldsValue({
                    reasoningEffort: options.defaultReasoningEffort,
                    contextWindow: options.defaultContextWindow,
                  });
                }} />
              </Form.Item>
              <Form.Item label="Reasoning Effort" name="reasoningEffort" rules={[{ required: true }]}>
                <Select options={qoderModelOptions.reasoningEfforts} />
              </Form.Item>
              <Form.Item label="Context Window" name="contextWindow" rules={[{ required: true }]}>
                <Select options={qoderModelOptions.contextWindows} />
              </Form.Item>
            </>
          )}
          <Form.Item label="执行器名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如: dev-machine-01" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title={`启动命令 · ${startupTarget?.name ?? ''}`} open={!!startupTarget} width={720}
        onCancel={() => setStartupTarget(null)}
        footer={(
          <Space size={8} wrap>
            <Button onClick={() => setStartupTarget(null)}>取消</Button>
            <Button loading={startupSaving}
              onClick={saveFromStartupModal}>保存配置</Button>
            <CopyCommandActions disabled={Boolean(startupConfigError) || !startupConfigured}
              onCopy={(shell) => copyFromStartupModal(shell)} />
          </Space>
        )}>
        {startupConfigError && (
          <Alert type="error" showIcon style={{ marginBottom: 16 }} message={startupConfigError} />
        )}
        {!startupConfigError && startupConfig && !startupConfigured && (
          <Alert type="warning" showIcon style={{ marginBottom: 16 }}
            message="该执行器尚未配置启动参数，请先选择并保存" />
        )}
        {startupModelInvalid && (
          <Alert type="warning" showIcon style={{ marginBottom: 16 }}
            message="当前模型已不可用，请重新选择" />
        )}
        <Form form={startupForm} layout="vertical" initialValues={{ maxConcurrentDispatches: 5 }}>
          <Form.Item label="最大并发任务数" name="maxConcurrentDispatches"
            rules={[{ required: true, type: 'integer', min: 1, max: 10, message: '请输入 1 到 10 的整数' }]}
            extra={<span style={{ whiteSpace: 'nowrap' }}>默认 5，范围 1–10，重启生效。</span>}>
            <InputNumber min={1} max={10} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="记忆模式" name="memoryMode" rules={[{ required: true }]}>
            <Select options={[
              { value: 'platform', label: '平台记忆（推荐）' },
              { value: 'provider-local', label: '本机 Agent 记忆' },
              { value: 'none', label: '关闭记忆' },
            ]} />
          </Form.Item>
          {Boolean(startupQoderProvider) && (
            <>
              <Form.Item label="Qoder 模型" name="model" rules={[{ required: true }]}>
                <Select options={startupQoderModelOptions} onChange={(model) => {
                  const options = qoderOptionsForModel(model);
                  startupForm.setFieldsValue({
                    reasoningEffort: options.defaultReasoningEffort,
                    contextWindow: options.defaultContextWindow,
                  });
                }} />
              </Form.Item>
              <Form.Item label="Reasoning Effort" name="reasoningEffort" rules={[{ required: true }]}>
                <Select options={startupQoderOptions.reasoningEfforts} />
              </Form.Item>
              <Form.Item label="Context Window" name="contextWindow" rules={[{ required: true }]}>
                <Select options={startupQoderOptions.contextWindows} />
              </Form.Item>
            </>
          )}
        </Form>
        <Typography.Text strong>命令预览</Typography.Text>
        <OsSegmented value={startupOs} onChange={setStartupOs} />
        <CommandPreview text={startupPreview} />
      </Modal>

      {/* Token Display Modal */}
      <Modal title="执行器创建成功" open={!!tokenResult} width={640}
        onOk={() => setTokenResult(null)} onCancel={() => setTokenResult(null)}
        cancelButtonProps={{ style: { display: 'none' } }}>
        <Alert type="warning" showIcon style={{ marginBottom: 16 }}
          message="请立即复制并保存 Token，关闭后将无法再次查看" />
        <Typography.Text strong>执行器名称: </Typography.Text>
        <Typography.Text>{tokenResult?.name}</Typography.Text>
        <div style={{ marginTop: 12 }}>
          <Typography.Text strong>Token:</Typography.Text>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4 }}>
            <Input.Password value={tokenResult?.token} readOnly style={{ flex: 1 }} />
            <Button icon={<CopyOutlined />} onClick={handleCopyToken}>复制</Button>
          </div>
        </div>
        {tokenResult && (
          <div style={{ marginTop: 16 }}>
            <Typography.Text strong>启动命令:</Typography.Text>
            <OsSegmented value={startupOs} onChange={setStartupOs} />
            <CommandPreview text={issuedPreview} />
            <div style={{ marginTop: 12 }}>
              <CopyCommandActions onCopy={(shell) => copyFromTokenModal(shell)} />
            </div>
          </div>
        )}
      </Modal>

      {/* 批量升级确认：先把影响面和目标版本说清楚，别让一次点击悄悄重启整个机群。 */}
      <Modal title="一键全量更新" open={updateAllOpen} okText="下发升级指令"
        onOk={() => updateAllMut.mutate()} onCancel={() => setUpdateAllOpen(false)}
        confirmLoading={updateAllMut.isPending}>
        <Alert type="warning" showIcon style={{ marginBottom: 16 }}
          message={`目标版本 ${autoUpdateQuery.data?.targetVersion ?? '-'}，当前筛选范围内有 ${upgradableExecutors.length} 个执行器可升级`}
          description="执行器会在派单间隙自行升级并重启，正在执行任务的执行器会被跳过。" />
        {autoUpdateQuery.data?.executorAutoUpdateEnabled === false && (
          <Typography.Paragraph type="secondary">
            自动升级当前处于关闭状态，被跳过的执行器不会自动重试，需要在结果里逐个处理。
          </Typography.Paragraph>
        )}
      </Modal>

      {/* 结果弹窗：跳过的机器必须逐条给出原因，否则操作者只看到「已下发 N 个」会以为全部成功。 */}
      <Modal title="全量更新已下发" open={Boolean(updateAllResult)} width={640}
        onOk={() => setUpdateAllResult(null)} onCancel={() => setUpdateAllResult(null)}
        cancelButtonProps={{ style: { display: 'none' } }}>
        {updateAllResult && (
          <>
            <Alert type={updateAllResult.skipped.length > 0 ? 'info' : 'success'} showIcon style={{ marginBottom: 16 }}
              message={describeUpdateAllResult(updateAllResult)} />
            {updateAllResult.skipped.length > 0 && (
              <div>
                <Typography.Text strong>被跳过的执行器</Typography.Text>
                <ul style={{ marginTop: 8, paddingLeft: 20 }}>
                  {updateAllResult.skipped.map((skip) => (
                    <li key={skip.executorId}>
                      <Typography.Text>{skip.executorName}</Typography.Text>
                      <Typography.Text type="secondary"> · {skip.reason}</Typography.Text>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </>
        )}
      </Modal>
    </div>
  );
}
