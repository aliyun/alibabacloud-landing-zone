import { useEffect, useMemo, useState } from 'react';
import { type FocusEvent, useRef, type KeyboardEvent } from 'react';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import {
  Alert, AutoComplete, Button, Card, Divider, Form, Input, Modal, Popconfirm, Result, Select,
  Empty, Space, Spin, Table, Tag, message,
} from 'antd';
import { ArrowLeftOutlined, SaveOutlined, SendOutlined, DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  useAgent, useAgentVersion, useEditConfig, useSubmitForReview, useUpdateAgent,
  useAddRepoPerm, useRemoveRepoPerm, useAddSkill, useRemoveSkill,
  useAddMemoryRef, useRemoveMemoryRef,
  useAddEnvironmentVariableRef, useRemoveEnvironmentVariableRef,
} from './hooks';
import { listRepos } from '@/features/repo/api';
import { listSkills } from '@/features/skill/api';
import { listMemories, type Memory } from '@/features/memory/api';
import { listSdlcTemplates } from '@/features/sdlc/api';
import { AGENT_ROLE_CODE_OPTIONS, AGENT_ROLE_NAME_OPTIONS, getRoleCodeByName, getRoleNameByCode } from './constants';
import type { ColumnsType } from 'antd/es/table';
import type { EvolutionMode, UpdateAgentRequest, UpdateConfigRequest } from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useAuthStore } from '@/shared/auth/store';
import { environmentVariablesQueryKey, useEnvironmentVariables } from '@/features/environmentVariables/hooks';
import type { AgentEnvironmentVariableRef } from './api';
import type { WorkspaceAccessLevel } from '@/shared/types/common';

const { TextArea } = Input;

interface RepoPermRow {
  repoId: number;
  repoName: string;
  permLevel: string;
  allowedBranchPatterns: string[];
}

interface SkillRow {
  skillId: number;
  skillName: string;
  type: 'SKILL' | 'MCP' | 'PLUGIN' | 'HOOK';
  version?: number;
}

interface MemoryRow {
  memoryId: number;
  title: string;
  source: string;
}

function extractList<T>(value: T[] | { list?: T[] } | undefined): T[] {
  if (!value) return [];
  return Array.isArray(value) ? value : (value.list ?? []);
}

function uniqueBy<T>(items: T[], keyOf: (item: T) => string | number): T[] {
  return [...new Map(items.map(item => [keyOf(item), item])).values()];
}

function errorMessage(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback;
}

function memoryTitle(memory: Memory | undefined, memoryId: number) {
  return memory?.title?.trim() || `#${memoryId}`;
}

function evolutionModeFromIdentity(identityJson?: string | null): EvolutionMode {
  if (!identityJson) return 'ASSISTED';
  try {
    const parsed = JSON.parse(identityJson) as { evolutionMode?: string };
    if (parsed.evolutionMode === 'MANUAL' || parsed.evolutionMode === 'ASSISTED' || parsed.evolutionMode === 'AUTO_PROPOSAL') {
      return parsed.evolutionMode;
    }
  } catch {
    // Ignore malformed legacy identity JSON and fall back to the safe assisted mode.
  }
  return 'ASSISTED';
}

const evolutionModeOptions = [
  {
    value: 'MANUAL',
    label: '纯手动',
  },
  {
    value: 'ASSISTED',
    label: '辅助审核（推荐）',
  },
  {
    value: 'AUTO_PROPOSAL',
    label: '自动生成候选',
  },
];

interface AgentFormValues {
  name?: string;
  avatarUrl?: string;
  roleName?: string;
  roleCode?: string;
  businessBackground?: string;
  responsibilities?: string;
  sdlcId?: number | null;
  evolutionMode?: EvolutionMode;
}

interface EnvironmentBindingOperation {
  context: string;
  generation: number;
  key: string;
  token: number;
}

interface EnvironmentBindingBoundary {
  workspaceId: number | undefined;
  agentId: number;
  accessLevel: WorkspaceAccessLevel | null;
  context: string;
  generation: number;
}

function environmentBindingContext(
  workspaceId: number | undefined,
  agentId: number,
  accessLevel: WorkspaceAccessLevel | null,
) {
  return `${workspaceId ?? 'none'}:${agentId}:${accessLevel ?? 'none'}`;
}

function environmentBindingServerScope(workspaceId: number | undefined, agentId: number) {
  return `${workspaceId ?? 'none'}:${agentId}`;
}

/** name/avatarUrl belong to the agent row and are patched separately, never sent to /config. */
function versionConfig(values: AgentFormValues): UpdateConfigRequest {
  return {
    roleName: values.roleName,
    roleCode: values.roleCode,
    businessBackground: values.businessBackground,
    responsibilities: values.responsibilities,
    sdlcId: values.sdlcId,
    evolutionMode: values.evolutionMode,
  };
}

export function AgentEditPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const agentId = Number(id);
  const [form] = Form.useForm();
  const businessBackgroundRef = useRef<TextAreaRef>(null);
  const accessCommand = useAccessCommand();
  const queryClient = useQueryClient();
  const workspaceId = useAuthStore((state) => state.currentWorkspace?.id);
  const accessLevel = useAuthStore((state) => state.accessLevel);
  const canManageEnvironmentVariables = useAuthStore((state) => state.hasAccess('READ_WRITE'));

  const { data: agent, isLoading, isError } = useAgent(agentId);
  const latestVersionNo = agent?.latestVersionNo ?? 0;
  const {
    data: versionDetail,
    isLoading: isVersionDetailLoading,
    isFetching: isVersionDetailFetching,
    isError: isVersionDetailError,
    error: versionDetailError,
    refetch: refetchVersionDetail,
  } = useAgentVersion(agentId, latestVersionNo);
  const isPlatform = agent?.kind === 'PLATFORM';

  const editConfig = useEditConfig();
  const updateAgent = useUpdateAgent();
  const submitForReview = useSubmitForReview();
  const addRepoPerm = useAddRepoPerm();
  const removeRepoPerm = useRemoveRepoPerm();
  const addSkillMut = useAddSkill();
  const removeSkillMut = useRemoveSkill();
  const addMemoryRef = useAddMemoryRef();
  const removeMemoryRef = useRemoveMemoryRef();
  const addEnvironmentVariableRef = useAddEnvironmentVariableRef();
  const removeEnvironmentVariableRef = useRemoveEnvironmentVariableRef();
  const environmentVariableLibrary = useEnvironmentVariables(canManageEnvironmentVariables);

  // Local state for relation tables (bound to agent's current relations)
  const [repoPerms, setRepoPerms] = useState<RepoPermRow[]>([]);
  const [skills, setSkills] = useState<SkillRow[]>([]);
  const [memories, setMemories] = useState<MemoryRow[]>([]);
  const [saveFeedback, setSaveFeedback] = useState<{ message: string; description: string } | null>(null);

  // Selector state
  const [repoModalOpen, setRepoModalOpen] = useState(false);
  const [skillModalOpen, setSkillModalOpen] = useState(false);
  const [memoryModalOpen, setMemoryModalOpen] = useState(false);
  const [selectedRepoIds, setSelectedRepoIds] = useState<number[]>([]);
  const [selectedPermLevel, setSelectedPermLevel] = useState('READ');
  const [selectedBranchPatterns, setSelectedBranchPatterns] = useState<string[]>([]);
  const [editingRepoPerm, setEditingRepoPerm] = useState<RepoPermRow | null>(null);
  const [editingPermLevel, setEditingPermLevel] = useState('READ');
  const [editingBranchPatterns, setEditingBranchPatterns] = useState<string[]>([]);
  const [selectedSkillIds, setSelectedSkillIds] = useState<number[]>([]);
  const [selectedMemoryIds, setSelectedMemoryIds] = useState<number[]>([]);
  const [environmentModalOpen, setEnvironmentModalOpen] = useState(false);
  const [selectedEnvironmentVariableId, setSelectedEnvironmentVariableId] = useState<number>();
  const [environmentBindingError, setEnvironmentBindingError] = useState<string>();
  const [environmentMountError, setEnvironmentMountError] = useState<string>();
  const [pendingEnvironmentOperation, setPendingEnvironmentOperation] = useState<EnvironmentBindingOperation>();
  const environmentOperationTokens = useRef(new Map<string, number>());
  const nextEnvironmentOperationToken = useRef(0);
  const pendingEnvironmentServerOperations = useRef(new Map<string, Map<number, string>>());
  const [, setEnvironmentServerOperationRevision] = useState(0);
  const [, setEnvironmentBoundaryRevision] = useState(0);
  const environmentBoundaryRef = useRef<EnvironmentBindingBoundary>({
    workspaceId,
    agentId,
    accessLevel,
    context: environmentBindingContext(workspaceId, agentId, accessLevel),
    generation: 0,
  });
  if (environmentBoundaryRef.current.agentId !== agentId
      || environmentBoundaryRef.current.workspaceId !== workspaceId
      || environmentBoundaryRef.current.accessLevel !== accessLevel) {
    const previousGeneration = environmentBoundaryRef.current.generation;
    environmentBoundaryRef.current = {
      workspaceId,
      agentId,
      accessLevel,
      context: environmentBindingContext(workspaceId, agentId, accessLevel),
      generation: previousGeneration + 1,
    };
    environmentOperationTokens.current.clear();
  }
  const environmentBoundaryGeneration = environmentBoundaryRef.current.generation;
  const environmentContext = environmentBoundaryRef.current.context;
  const currentEnvironmentServerScope = environmentBindingServerScope(workspaceId, agentId);
  const environmentServerOperationPending = (pendingEnvironmentServerOperations.current
    .get(currentEnvironmentServerScope)?.size ?? 0) > 0;
  const environmentOperationPending = pendingEnvironmentOperation?.context === environmentContext
    && pendingEnvironmentOperation.generation === environmentBoundaryGeneration;
  const environmentVersionReady = Boolean(versionDetail)
    && !isVersionDetailLoading
    && !isVersionDetailError;

  const isEnvironmentOperationCurrent = (operation: EnvironmentBindingOperation) => {
    const boundary = environmentBoundaryRef.current;
    return boundary.context === operation.context
      && boundary.generation === operation.generation
      && environmentOperationTokens.current.get(operation.key) === operation.token;
  };
  const hasPendingEnvironmentServerOperation = () =>
    (pendingEnvironmentServerOperations.current
      .get(environmentBindingServerScope(
        useAuthStore.getState().currentWorkspace?.id,
        agentId,
      ))?.size ?? 0) > 0;

  // Reference data — backend returns raw arrays; API types say PageResult but runtime is T[]
  const { data: reposRaw } = useQuery({ queryKey: ['repos', 1, 100], queryFn: () => listRepos({ page: 1, size: 100 }) });
  const { data: skillsRaw } = useQuery({ queryKey: ['skills', 1, 100], queryFn: () => listSkills({ page: 1, size: 100 }) });
  // 不按 status 过滤：已绑定记忆可能是历史 PENDING/REJECTED 引用，需要全量列表才能解析出标题；
  // “仅已审核可选”的限制加在导入下拉的候选项上。
  const { data: memoriesRaw } = useQuery({ queryKey: ['memories', 1, 100], queryFn: () => listMemories({ page: 1, size: 100 }) });
  const { data: sdlcsRaw } = useQuery({ queryKey: ['sdlcs', 1, 100], queryFn: () => listSdlcTemplates({ page: 1, size: 100 }) });
  // Safe extract: handle both PageResult and raw array
  const reposList = useMemo(() => extractList(reposRaw), [reposRaw]);
  const skillsList = useMemo(() => extractList(skillsRaw), [skillsRaw]);
  const memoriesList = useMemo(() => extractList(memoriesRaw), [memoriesRaw]);
  const sdlcsList = useMemo(() => extractList(sdlcsRaw), [sdlcsRaw]);
  const mountedEnvironmentVariables = useMemo(
    () => uniqueBy(versionDetail?.environmentVariables ?? [], variable => variable.id),
    [versionDetail],
  );
  const availableEnvironmentVariables = useMemo(() => {
    const mountedIds = new Set(mountedEnvironmentVariables.map(variable => variable.id));
    return (environmentVariableLibrary.data ?? []).filter(variable => !mountedIds.has(variable.id));
  }, [environmentVariableLibrary.data, mountedEnvironmentVariables]);

  useEffect(() => useAuthStore.subscribe((state, previous) => {
    const nextWorkspaceId = state.currentWorkspace?.id;
    const previousWorkspaceId = previous.currentWorkspace?.id;
    if (nextWorkspaceId === previousWorkspaceId && state.accessLevel === previous.accessLevel) return;
    const current = environmentBoundaryRef.current;
    environmentBoundaryRef.current = {
      workspaceId: nextWorkspaceId,
      agentId: current.agentId,
      accessLevel: state.accessLevel,
      context: environmentBindingContext(nextWorkspaceId, current.agentId, state.accessLevel),
      generation: current.generation + 1,
    };
    environmentOperationTokens.current.clear();
    setEnvironmentModalOpen(false);
    setSelectedEnvironmentVariableId(undefined);
    setEnvironmentBindingError(undefined);
    setEnvironmentMountError(undefined);
    setPendingEnvironmentOperation(undefined);
    setEnvironmentBoundaryRevision(revision => revision + 1);
  }), []);

  useEffect(() => {
    setEnvironmentModalOpen(false);
    setSelectedEnvironmentVariableId(undefined);
    setEnvironmentBindingError(undefined);
    setEnvironmentMountError(undefined);
    setPendingEnvironmentOperation(undefined);
    environmentOperationTokens.current.clear();
  }, [environmentBoundaryGeneration]);

  useEffect(() => {
    if (agent) {
      form.setFieldsValue({ name: agent.name, avatarUrl: agent.avatarUrl ?? '' });
    }
  }, [agent, form]);

  useEffect(() => {
    if (versionDetail) {
      form.setFieldsValue({
        roleName: versionDetail.roleName,
        roleCode: versionDetail.roleCode,
        businessBackground: versionDetail.businessBackground,
        responsibilities: versionDetail.responsibilities,
        sdlcId: versionDetail.sdlcId,
        evolutionMode: versionDetail.evolutionMode || evolutionModeFromIdentity(versionDetail.identityJson),
      });
    }
  }, [versionDetail, form]);

  useEffect(() => {
    if (!versionDetail) return;

    setRepoPerms(uniqueBy((versionDetail.repoPerms ?? []).map((perm) => {
      const repo = reposList.find((item) => item.id === perm.repoId);
      return {
        repoId: perm.repoId,
        repoName: repo?.name || `#${perm.repoId}`,
        permLevel: perm.permLevel,
        allowedBranchPatterns: perm.allowedBranchPatterns ?? [],
      };
    }), item => item.repoId));

    setSkills(uniqueBy((versionDetail.skills ?? []).map((skillRef) => {
      const skill = skillsList.find((item) => item.id === skillRef.skillId);
      return {
        skillId: skillRef.skillId,
        skillName: skill?.name || `#${skillRef.skillId}`,
        type: skill?.type || 'SKILL',
        version: skill?.version,
      };
    }), item => item.skillId));

    setMemories(uniqueBy((versionDetail.memoryRefs ?? []).map((memoryRef) => {
      const memory = memoriesList.find((item) => item.id === memoryRef.memoryId);
      return {
        memoryId: memoryRef.memoryId,
        title: memoryTitle(memory, memoryRef.memoryId),
        source: memoryRef.source,
      };
    }), item => item.memoryId));
  }, [versionDetail, reposList, skillsList, memoriesList]);

  if (!agentId || isNaN(agentId)) return (
    <Result status="404" title="无效的 ID" extra={<Button onClick={() => navigate(-1)}>返回</Button>} />
  );
  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (isError || !agent) return (
    <Result status="error" title="加载失败" extra={<Button onClick={() => navigate(-1)}>返回</Button>} />
  );

  // Only patch when a value actually changed: an unconditional PATCH would bump the agent's
  // optimistic-lock version on every draft save and can trip a spurious version conflict.
  const buildAgentPatch = (values: AgentFormValues): UpdateAgentRequest => {
    const patch: UpdateAgentRequest = {};
    const name = (values.name ?? '').trim();
    if (name && name !== agent.name) {
      patch.name = name;
    }
    const avatarUrl = (values.avatarUrl ?? '').trim();
    if (avatarUrl !== (agent.avatarUrl ?? '')) {
      patch.avatarUrl = avatarUrl === '' ? null : avatarUrl;
    }
    return patch;
  };

  const persistForm = async (values: AgentFormValues): Promise<boolean> => {
    const patch = buildAgentPatch(values);
    const agentPatched = Object.keys(patch).length > 0;
    if (agentPatched) {
      await updateAgent.mutateAsync({ id: agentId, payload: patch });
    }
    await editConfig.mutateAsync({ agentId, config: versionConfig(values) });
    return agentPatched;
  };

  const handleSave = () => {
    if (hasPendingEnvironmentServerOperation()) {
      message.warning('环境变量挂载变更处理中，请稍后保存');
      return;
    }
    accessCommand('READ_WRITE', '保存数字员工配置', async () => {
      try {
        const values: AgentFormValues = await form.validateFields();
        const agentPatched = await persistForm(values);
        message.success(agentPatched ? '名称与配置已保存' : '配置已保存为草稿');
        setSaveFeedback(agentPatched
          ? {
            message: '已保存',
            description: '名称与头像已立即生效；角色配置已写入草稿，可继续编辑或提交审核。',
          }
          : {
            message: '草稿已保存',
            description: '当前修改已写入草稿，可继续编辑或提交审核。',
          });
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message : '保存失败';
        if (msg !== '保存失败') message.error(msg);
      }
    });
  };

  const handleSubmit = () => {
    if (hasPendingEnvironmentServerOperation()) {
      message.warning('环境变量挂载变更处理中，请稍后提交审核');
      return;
    }
    accessCommand('READ_WRITE', '提交数字员工审核', async () => {
      try {
        const values: AgentFormValues = await form.validateFields();
        await persistForm(values);
        await submitForReview.mutateAsync(agentId);
        message.success('已提交审核');
        navigate(`/agents/${agentId}`);
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message : '提交失败';
        message.error(msg);
      }
    });
  };

  const handleRoleNameSelect = (value: string) => {
    const roleCode = getRoleCodeByName(value);
    if (roleCode) {
      form.setFieldsValue({ roleCode });
    }
  };

  const handleRoleCodeSelect = (value: string) => {
    const roleName = getRoleNameByCode(value);
    if (roleName) {
      form.setFieldsValue({ roleName });
    }
  };

  const handleRoleCodeBlur = (e: FocusEvent<HTMLInputElement>) => {
    const normalized = e.target.value.trim().toUpperCase();
    form.setFieldsValue({ roleCode: normalized });
  };

  const handleRoleCodeKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === ' ') {
      e.preventDefault();
      const normalized = e.currentTarget.value.trim().toUpperCase();
      form.setFieldsValue({ roleCode: normalized });
      businessBackgroundRef.current?.focus();
    }
  };

  const handleAddRepo = () => {
    if (selectedRepoIds.length === 0) return;
    accessCommand('READ_WRITE', '添加数字员工仓库', async () => {
      try {
        await Promise.all(selectedRepoIds.map(repoId =>
          addRepoPerm.mutateAsync({ agentId, repoId, permLevel: selectedPermLevel, allowedBranchPatterns: selectedBranchPatterns })));
        const added = selectedRepoIds.map(repoId => {
          const repo = reposList.find(r => r.id === repoId);
          return { repoId, repoName: repo?.name || `#${repoId}`, permLevel: selectedPermLevel, allowedBranchPatterns: selectedBranchPatterns };
        });
        setRepoPerms(prev => uniqueBy([...prev, ...added], item => item.repoId));
        setRepoModalOpen(false);
        setSelectedRepoIds([]);
        setSelectedBranchPatterns([]);
      } catch (e) {
        message.error(errorMessage(e, '添加仓库失败'));
      }
    });
  };

  const openEditRepoPerm = (repo: RepoPermRow) => {
    accessCommand('READ_WRITE', '编辑数字员工仓库权限', () => {
      setEditingRepoPerm(repo);
      setEditingPermLevel(repo.permLevel);
      setEditingBranchPatterns(repo.allowedBranchPatterns);
    });
  };

  const handleEditRepoPerm = () => {
    if (!editingRepoPerm) return;
    accessCommand('READ_WRITE', '编辑数字员工仓库权限', async () => {
      try {
        await addRepoPerm.mutateAsync({
          agentId,
          repoId: editingRepoPerm.repoId,
          permLevel: editingPermLevel,
          allowedBranchPatterns: editingBranchPatterns,
        });
        setRepoPerms(previous => previous.map(repo => repo.repoId === editingRepoPerm.repoId
          ? { ...repo, permLevel: editingPermLevel, allowedBranchPatterns: editingBranchPatterns }
          : repo));
        setEditingRepoPerm(null);
      } catch (e) {
        message.error(errorMessage(e, '编辑仓库权限失败'));
      }
    });
  };

  const handleRemoveRepo = (repoId: number) => {
    accessCommand('READ_WRITE', '移除数字员工仓库', () => {
      removeRepoPerm.mutate({ agentId, repoId }, {
        onSuccess: () => setRepoPerms(prev => prev.filter(r => r.repoId !== repoId)),
        onError: (e) => message.error(errorMessage(e, '移除仓库失败')),
      });
    });
  };

  const handleAddSkill = () => {
    if (selectedSkillIds.length === 0) return;
    accessCommand('READ_WRITE', '添加数字员工能力', async () => {
      try {
        await Promise.all(selectedSkillIds.map(skillId => addSkillMut.mutateAsync({ agentId, skillId })));
        const added = selectedSkillIds.map(skillId => {
          const skill = skillsList.find(s => s.id === skillId);
          return {
            skillId,
            skillName: skill?.name || `#${skillId}`,
            type: skill?.type || 'SKILL',
            version: skill?.version,
          };
        });
        setSkills(prev => uniqueBy([...prev, ...added], item => item.skillId));
        setSkillModalOpen(false);
        setSelectedSkillIds([]);
      } catch (e) {
        message.error(errorMessage(e, '添加技能失败'));
      }
    });
  };

  const handleRemoveSkill = (skillId: number) => {
    accessCommand('READ_WRITE', '移除数字员工能力', () => {
      removeSkillMut.mutate({ agentId, skillId }, {
        onSuccess: () => setSkills(prev => prev.filter(s => s.skillId !== skillId)),
        onError: (e) => message.error(errorMessage(e, '移除技能失败')),
      });
    });
  };

  const handleAddMemory = () => {
    if (selectedMemoryIds.length === 0) return;
    accessCommand('READ_WRITE', '导入数字员工记忆', async () => {
      try {
        await Promise.all(selectedMemoryIds.map(memoryId =>
          addMemoryRef.mutateAsync({ agentId, memoryId, source: 'ORG' })));
        const added = selectedMemoryIds.map(memoryId => {
          const mem = memoriesList.find(m => m.id === memoryId);
          return { memoryId, title: memoryTitle(mem, memoryId), source: 'ORG' };
        });
        setMemories(prev => uniqueBy([...prev, ...added], item => item.memoryId));
        setMemoryModalOpen(false);
        setSelectedMemoryIds([]);
      } catch (e) {
        message.error(errorMessage(e, '导入记忆失败'));
      }
    });
  };

  const handleRemoveMemory = (memoryId: number) => {
    accessCommand('READ_WRITE', '移除数字员工记忆', () => {
      removeMemoryRef.mutate({ agentId, memoryId }, {
        onSuccess: () => setMemories(prev => prev.filter(m => m.memoryId !== memoryId)),
        onError: (e) => message.error(errorMessage(e, '移除记忆失败')),
      });
    });
  };

  const handleAddEnvironmentVariable = () => {
    if (!environmentVersionReady || !selectedEnvironmentVariableId || environmentOperationPending) return;
    const environmentVariableId = selectedEnvironmentVariableId;
    const operationKey = `mount:${environmentVariableId}`;
    if (environmentOperationTokens.current.has(operationKey)) return;
    accessCommand('READ_WRITE', '挂载数字员工环境变量', async () => {
      const auth = useAuthStore.getState();
      const operationWorkspaceId = auth.currentWorkspace?.id;
      const operationAccessLevel = auth.accessLevel;
      const operationContext = environmentBindingContext(operationWorkspaceId, agentId, operationAccessLevel);
      const operationGeneration = environmentBoundaryRef.current.generation;
      if (!operationWorkspaceId || operationContext !== environmentBoundaryRef.current.context) return;
      const operationToken = ++nextEnvironmentOperationToken.current;
      const operation = {
        context: operationContext,
        generation: operationGeneration,
        key: operationKey,
        token: operationToken,
      };
      environmentOperationTokens.current.set(operationKey, operationToken);
      const operationServerScope = environmentBindingServerScope(operationWorkspaceId, agentId);
      const scopeOperations = pendingEnvironmentServerOperations.current.get(operationServerScope)
        ?? new Map<number, string>();
      scopeOperations.set(operationToken, operationKey);
      pendingEnvironmentServerOperations.current.set(operationServerScope, scopeOperations);
      setEnvironmentServerOperationRevision(revision => revision + 1);
      setPendingEnvironmentOperation(operation);
      setEnvironmentMountError(undefined);
      let operationFailed = false;
      try {
        await addEnvironmentVariableRef.mutateAsync({
          agentId,
          environmentVariableId,
        });
        await Promise.all([
          queryClient.invalidateQueries({
            queryKey: ['agent', agentId, 'detail', operationWorkspaceId],
            exact: true,
          }),
          queryClient.invalidateQueries({
            queryKey: ['agent', agentId, 'version', latestVersionNo, operationWorkspaceId],
            exact: true,
          }),
          queryClient.invalidateQueries({
            queryKey: environmentVariablesQueryKey(operationWorkspaceId),
            exact: true,
          }),
        ]);
        if (isEnvironmentOperationCurrent(operation)) {
          environmentOperationTokens.current.delete(`unmount:${environmentVariableId}`);
          setEnvironmentModalOpen(false);
          setSelectedEnvironmentVariableId(undefined);
          setEnvironmentMountError(undefined);
        }
      } catch (e) {
        operationFailed = true;
        if (isEnvironmentOperationCurrent(operation)) {
          setEnvironmentMountError(errorMessage(e, '挂载环境变量失败'));
        }
      } finally {
        if (isEnvironmentOperationCurrent(operation)) {
          setPendingEnvironmentOperation(previous => previous?.token === operationToken
            && previous.context === operationContext
            && previous.generation === operationGeneration ? undefined : previous);
          if (operationFailed) {
            environmentOperationTokens.current.delete(operationKey);
          }
        }
        const pendingScopeOperations = pendingEnvironmentServerOperations.current.get(operationServerScope);
        pendingScopeOperations?.delete(operationToken);
        if (pendingScopeOperations?.size === 0) {
          pendingEnvironmentServerOperations.current.delete(operationServerScope);
        }
        setEnvironmentServerOperationRevision(revision => revision + 1);
      }
    });
  };

  const handleRemoveEnvironmentVariable = (variable: AgentEnvironmentVariableRef) => {
    if (!environmentVersionReady || environmentOperationPending) return;
    const operationKey = `unmount:${variable.id}`;
    if (environmentOperationTokens.current.has(operationKey)) return;
    accessCommand('READ_WRITE', '解绑数字员工环境变量', async () => {
      const auth = useAuthStore.getState();
      const operationWorkspaceId = auth.currentWorkspace?.id;
      const operationAccessLevel = auth.accessLevel;
      const operationContext = environmentBindingContext(operationWorkspaceId, agentId, operationAccessLevel);
      const operationGeneration = environmentBoundaryRef.current.generation;
      if (!operationWorkspaceId || operationContext !== environmentBoundaryRef.current.context) return;
      const operationToken = ++nextEnvironmentOperationToken.current;
      const operation = {
        context: operationContext,
        generation: operationGeneration,
        key: operationKey,
        token: operationToken,
      };
      environmentOperationTokens.current.set(operationKey, operationToken);
      const operationServerScope = environmentBindingServerScope(operationWorkspaceId, agentId);
      const scopeOperations = pendingEnvironmentServerOperations.current.get(operationServerScope)
        ?? new Map<number, string>();
      scopeOperations.set(operationToken, operationKey);
      pendingEnvironmentServerOperations.current.set(operationServerScope, scopeOperations);
      setEnvironmentServerOperationRevision(revision => revision + 1);
      setPendingEnvironmentOperation(operation);
      setEnvironmentBindingError(undefined);
      let operationFailed = false;
      try {
        await removeEnvironmentVariableRef.mutateAsync({
          agentId,
          environmentVariableId: variable.id,
        });
        await Promise.all([
          queryClient.invalidateQueries({
            queryKey: ['agent', agentId, 'detail', operationWorkspaceId],
            exact: true,
          }),
          queryClient.invalidateQueries({
            queryKey: ['agent', agentId, 'version', latestVersionNo, operationWorkspaceId],
            exact: true,
          }),
          queryClient.invalidateQueries({
            queryKey: environmentVariablesQueryKey(operationWorkspaceId),
            exact: true,
          }),
        ]);
        if (isEnvironmentOperationCurrent(operation)) {
          environmentOperationTokens.current.delete(`mount:${variable.id}`);
        }
      } catch (e) {
        operationFailed = true;
        if (isEnvironmentOperationCurrent(operation)) {
          setEnvironmentBindingError(errorMessage(e, '解绑环境变量失败'));
        }
      } finally {
        if (isEnvironmentOperationCurrent(operation)) {
          setPendingEnvironmentOperation(previous => previous?.token === operationToken
            && previous.context === operationContext
            && previous.generation === operationGeneration ? undefined : previous);
          if (operationFailed) {
            environmentOperationTokens.current.delete(operationKey);
          }
        }
        const pendingScopeOperations = pendingEnvironmentServerOperations.current.get(operationServerScope);
        pendingScopeOperations?.delete(operationToken);
        if (pendingScopeOperations?.size === 0) {
          pendingEnvironmentServerOperations.current.delete(operationServerScope);
        }
        setEnvironmentServerOperationRevision(revision => revision + 1);
      }
    });
  };

  const repoColumns: ColumnsType<RepoPermRow> = [
    { title: '仓库', dataIndex: 'repoName' },
    { title: '权限', dataIndex: 'permLevel', width: 100, render: (v: string) => <Tag>{v}</Tag> },
    ...(isPlatform ? [] : ([
      {
        title: '提交分支', dataIndex: 'allowedBranchPatterns',
        render: (patterns: string[]) => patterns.length === 0
          ? <span style={{ color: '#999' }}>不限制</span>
          : <Space size={[0, 4]} wrap>{patterns.map(pattern => <Tag key={pattern}>{pattern}</Tag>)}</Space>,
      },
      {
        title: '操作', width: 120,
        render: (_: unknown, r: RepoPermRow) => (
          <Space size={0}>
            <Button type="link" size="small" aria-label={`编辑 ${r.repoName} 分支规则`}
              icon={<EditOutlined />} onClick={() => openEditRepoPerm(r)} />
            <Popconfirm title="确认移除?" onConfirm={() => handleRemoveRepo(r.repoId)}>
              <Button type="link" size="small" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          </Space>
        ),
      },
    ] as ColumnsType<RepoPermRow>)),
  ];

  const skillColumns: ColumnsType<SkillRow> = [
    { title: '能力', dataIndex: 'skillName' },
    { title: '类型', dataIndex: 'type', width: 110, render: (value: string) => <Tag>{value}</Tag> },
    { title: '版本', dataIndex: 'version', width: 80, render: (value?: number) => value ?? '-' },
    {
      title: '操作', width: 80,
      render: (_: unknown, r: SkillRow) => (
        <Popconfirm title="确认移除?" onConfirm={() => handleRemoveSkill(r.skillId)}>
          <Button type="link" size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
  ];

  const memoryColumns: ColumnsType<MemoryRow> = [
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '来源', dataIndex: 'source', width: 80, render: (v: string) => <Tag>{v}</Tag> },
    {
      title: '操作', width: 80,
      render: (_: unknown, r: MemoryRow) => (
        <Popconfirm title="确认移除?" onConfirm={() => handleRemoveMemory(r.memoryId)}>
          <Button type="link" size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
  ];

  const environmentVariableColumns: ColumnsType<AgentEnvironmentVariableRef> = [
    { title: '变量名', dataIndex: 'name', width: '36%' },
    {
      title: '说明',
      dataIndex: 'description',
      render: (description: string | null) => description || <span style={{ color: '#999' }}>暂无说明</span>,
    },
    ...(canManageEnvironmentVariables ? ([{
      title: '操作',
      width: 80,
      render: (_: unknown, variable: AgentEnvironmentVariableRef) => (
        <Popconfirm
          title={`确认解绑 ${variable.name}?`}
          okText="确认解绑"
          cancelText="取消"
          onConfirm={() => handleRemoveEnvironmentVariable(variable)}
        >
          <Button
            type="link"
            size="small"
            danger
            aria-label={`解绑 ${variable.name}`}
            icon={<DeleteOutlined />}
            disabled={environmentOperationPending}
          />
        </Popconfirm>
      ),
    }] as ColumnsType<AgentEnvironmentVariableRef>) : []),
  ];

  return (
    <div>
      <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate(`/agents/${agentId}`)} style={{ marginBottom: 16, padding: 0 }}>
        返回详情
      </Button>

      {saveFeedback && (
        <Alert
          showIcon
          type="success"
          message={saveFeedback.message}
          description={saveFeedback.description}
          style={{ marginBottom: 16 }}
        />
      )}

      <Card title={`编辑配置 — ${agent.name}`}
        extra={
          <Space>
            <Button icon={<SaveOutlined />} onClick={handleSave}
              loading={editConfig.isPending || updateAgent.isPending}
              disabled={environmentServerOperationPending}>
              保存草稿
            </Button>
            <Button type="primary" icon={<SendOutlined />} onClick={handleSubmit}
              loading={submitForReview.isPending}
              disabled={environmentServerOperationPending}>
              提交审核
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" style={{ maxWidth: 800 }}>
          <Form.Item
            label="员工名称"
            name="name"
            rules={[{ required: true, message: '请输入员工名称' }]}
            extra={isPlatform
              ? '平台智能体的名称由平台锁定，不可修改。'
              : '数字员工的展示名称，保存后立即生效，不需要提交审核。与下方的“角色名称”是两个不同的字段。'}
          >
            <Input placeholder="如: 前端开发小明" maxLength={64} disabled={isPlatform} />
          </Form.Item>
          <Form.Item
            label="头像 URL"
            name="avatarUrl"
            extra={isPlatform
              ? '平台智能体的头像由平台锁定，不可修改。'
              : '留空表示清除头像。与员工名称一样保存后立即生效。'}
          >
            <Input placeholder="https://..." allowClear disabled={isPlatform} />
          </Form.Item>
          <Form.Item label="角色名称" name="roleName" rules={[{ required: true, message: '请输入角色名称' }]}>
            <AutoComplete
              options={AGENT_ROLE_NAME_OPTIONS}
              allowClear
              placeholder="如: 前端开发工程师"
              onSelect={handleRoleNameSelect}
            />
          </Form.Item>
          <Form.Item label="角色码" name="roleCode" rules={[{ required: true, message: '请输入角色码' }]}>
            <AutoComplete
              options={AGENT_ROLE_CODE_OPTIONS}
              allowClear
              placeholder="如: FRONTEND_DEV"
              onSelect={handleRoleCodeSelect}
              onBlur={handleRoleCodeBlur}
              onKeyDown={handleRoleCodeKeyDown}
            />
          </Form.Item>
          <Form.Item label="SOUL.md" name="businessBackground">
            <TextArea
              ref={businessBackgroundRef}
              rows={4}
              placeholder="描述该员工所在的业务背景..."
            />
          </Form.Item>
          <Form.Item label="AGENT.md" name="responsibilities">
            <TextArea rows={4} placeholder="描述该员工的核心职责..." />
          </Form.Item>
          {!isPlatform && (
            <Form.Item label="SDLC 模版" name="sdlcId">
              <Select allowClear placeholder="选择关联的 SDLC 模版"
                options={sdlcsList.map(s => ({ value: s.id, label: `${s.name} (${s.status})` })) || []}
              />
            </Form.Item>
          )}
          <Form.Item
            label="自进化模式"
            name="evolutionMode"
            tooltip="控制 worker 上传 learning_delta 后，服务端是否自动进入待审核 Memory / Evolution Proposal。不会自动发布 active 资产。"
          >
            <Select
              aria-label="自进化模式"
              options={evolutionModeOptions}
              optionRender={(option) => {
                const descriptions: Record<string, string> = {
                  MANUAL: '只保存 artifact，不自动沉淀记忆或候选',
                  ASSISTED: '自动生成待审核记忆和候选，不自动上线',
                  AUTO_PROPOSAL: '自动生成候选，并允许候选自带 replay 时自动验证',
                };
                return (
                  <Space direction="vertical" size={0}>
                    <span>{option.label}</span>
                    <span style={{ fontSize: 12, color: '#888' }}>{descriptions[String(option.value)]}</span>
                  </Space>
                );
              }}
            />
          </Form.Item>
        </Form>
      </Card>

      <Divider />

      {/* Repo Permissions */}
      <Card title="仓库权限" style={{ marginTop: 16 }}
        extra={isPlatform ? null : (
          <Button size="small" icon={<PlusOutlined />}
            onClick={() => accessCommand('READ_WRITE', '添加数字员工仓库', () => setRepoModalOpen(true))}>
            添加仓库
          </Button>
        )}
      >
        {isPlatform && (
          <Alert
            type="info"
            showIcon
            message="无需配置平台智能体的仓库配置"
            description="其默认拥有平台所有的仓库的读取权限，进行平台智能的管理。"
            style={{ marginBottom: 12 }}
          />
        )}
        <Table rowKey="repoId" columns={repoColumns} dataSource={repoPerms} pagination={false} size="small" />
      </Card>

      {/* Capabilities */}
      <Card title="能力配置" style={{ marginTop: 16 }}
        extra={<Button size="small" icon={<PlusOutlined />}
          onClick={() => accessCommand('READ_WRITE', '添加数字员工能力', () => setSkillModalOpen(true))}>
          添加能力
        </Button>}
      >
        <Alert
          type="info"
          showIcon
          message="AutoWonder MCP 已内置"
          description="每次任务会自动使用任务级凭证装载，无需手动绑定，也不会暴露个人长期 Token。"
          style={{ marginBottom: 12 }}
        />
        <Table rowKey="skillId" columns={skillColumns} dataSource={skills} pagination={false} size="small" />
      </Card>

      {/* Memory */}
      <Card title="记忆导入" style={{ marginTop: 16 }}
        extra={<Button size="small" icon={<PlusOutlined />}
          onClick={() => accessCommand('READ_WRITE', '导入数字员工记忆', () => setMemoryModalOpen(true))}>
          导入记忆
        </Button>}
      >
        <Table rowKey="memoryId" columns={memoryColumns} dataSource={memories} pagination={false} size="small" />
      </Card>

      {/* Environment variables are metadata-only here; values remain confined to the library reveal flow. */}
      <Card title="环境变量" style={{ marginTop: 16 }}
        extra={canManageEnvironmentVariables ? (
          <Button
            size="small"
            icon={<PlusOutlined />}
            onClick={() => {
              environmentOperationTokens.current.clear();
              setEnvironmentBindingError(undefined);
              setEnvironmentMountError(undefined);
              setEnvironmentModalOpen(true);
            }}
            disabled={environmentOperationPending || !environmentVersionReady}
          >
            挂载环境变量
          </Button>
        ) : null}
      >
        <Alert
          type="info"
          showIcon
          message="挂载或解绑会进入数字员工草稿，并遵循审核和发布流程。"
          description="变量库中的值更新无需数字员工审核，将在下一次任务派发或对话轮次生效。"
          style={{ marginBottom: 12 }}
        />
        {environmentBindingError && (
          <Alert
            type="error"
            showIcon
            message={environmentBindingError}
            closable
            onClose={() => setEnvironmentBindingError(undefined)}
            style={{ marginBottom: 12 }}
          />
        )}
        {isVersionDetailLoading ? (
          <Space>
            <Spin size="small" />
            <span>正在加载环境变量挂载...</span>
          </Space>
        ) : isVersionDetailError ? (
          <Alert
            type="error"
            showIcon
            message="环境变量挂载加载失败"
            description={errorMessage(versionDetailError, '版本详情加载失败')}
            action={(
              <Button size="small" loading={isVersionDetailFetching} onClick={() => refetchVersionDetail()}>
                重试
              </Button>
            )}
          />
        ) : (
          <Table
            rowKey="id"
            columns={environmentVariableColumns}
            dataSource={mountedEnvironmentVariables}
            pagination={false}
            size="small"
            locale={{ emptyText: '暂无已挂载的环境变量' }}
          />
        )}
      </Card>

      {/* Add Repo Modal */}
      <Modal title="添加仓库权限" open={repoModalOpen} onOk={handleAddRepo} onCancel={() => setRepoModalOpen(false)}
        okButtonProps={{ disabled: selectedRepoIds.length === 0 }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Select mode="multiple" placeholder="选择仓库（可多选）" style={{ width: '100%' }} value={selectedRepoIds}
            onChange={setSelectedRepoIds} showSearch optionFilterProp="label"
            options={reposList.filter(r => !repoPerms.some(p => p.repoId === r.id))
              .map(r => ({ value: r.id, label: r.name })) || []}
          />
          <Select value={selectedPermLevel} onChange={setSelectedPermLevel} style={{ width: '100%' }}
            options={[
              { value: 'READ', label: '只读' },
              { value: 'WRITE', label: '读写' },
              { value: 'ADMIN', label: '管理' },
            ]}
          />
          <Select mode="tags" aria-label="允许提交的分支" value={selectedBranchPatterns}
            onChange={setSelectedBranchPatterns} tokenSeparators={[',']} style={{ width: '100%' }}
            placeholder="允许提交的分支（留空表示不限制，如 develop、feature/*）" maxCount={32}
          />
        </Space>
      </Modal>

      <Modal title="编辑仓库权限" open={editingRepoPerm !== null} onOk={handleEditRepoPerm}
        onCancel={() => setEditingRepoPerm(null)}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input value={editingRepoPerm?.repoName} disabled />
          <Select value={editingPermLevel} onChange={setEditingPermLevel} style={{ width: '100%' }}
            options={[
              { value: 'READ', label: '只读' },
              { value: 'WRITE', label: '读写' },
              { value: 'ADMIN', label: '管理' },
            ]}
          />
          <Select mode="tags" aria-label="允许提交的分支" value={editingBranchPatterns}
            onChange={setEditingBranchPatterns} tokenSeparators={[',']} style={{ width: '100%' }}
            placeholder="留空表示不限制，如 develop、release/autowonder-v*" maxCount={32}
          />
        </Space>
      </Modal>

      {/* Add Capability Modal */}
      <Modal title="添加能力" open={skillModalOpen} onOk={handleAddSkill} onCancel={() => setSkillModalOpen(false)}
        okButtonProps={{ disabled: selectedSkillIds.length === 0 }}>
        <Select mode="multiple" placeholder="选择 Skill、MCP 或 Plugin（可多选）" style={{ width: '100%' }} value={selectedSkillIds}
          onChange={setSelectedSkillIds} showSearch optionFilterProp="label"
          options={skillsList.filter(s => !skills.some(sk => sk.skillId === s.id))
            .map(s => ({ value: s.id, label: `${s.name} (${s.type})` })) || []}
        />
      </Modal>

      {/* Add Memory Modal */}
      <Modal title="导入记忆" open={memoryModalOpen} onOk={handleAddMemory} onCancel={() => setMemoryModalOpen(false)}
        okButtonProps={{ disabled: selectedMemoryIds.length === 0 }}>
        <Select mode="multiple" placeholder="选择记忆（可多选）" style={{ width: '100%' }} value={selectedMemoryIds}
          onChange={setSelectedMemoryIds} showSearch optionFilterProp="label"
          options={memoriesList
            .filter(m => m.status === 'ADOPTED' && !memories.some(me => me.memoryId === m.id))
            .map(m => ({ value: m.id, label: memoryTitle(m, m.id) }))}
        />
      </Modal>

      <Modal
        title="挂载环境变量"
        open={environmentModalOpen && canManageEnvironmentVariables}
        okText="挂载"
        cancelText="取消"
        onOk={handleAddEnvironmentVariable}
        onCancel={() => {
          setEnvironmentModalOpen(false);
          setSelectedEnvironmentVariableId(undefined);
          setEnvironmentMountError(undefined);
        }}
        confirmLoading={environmentOperationPending && pendingEnvironmentOperation?.key.startsWith('mount:')}
        okButtonProps={{
          disabled: selectedEnvironmentVariableId === undefined
            || environmentOperationPending
            || !environmentVersionReady,
        }}
      >
        {environmentMountError && (
          <Alert
            type="error"
            showIcon
            message={environmentMountError}
            style={{ marginBottom: 12 }}
          />
        )}
        {!environmentVersionReady ? (
          <Alert type="warning" showIcon message="请先等待数字员工版本详情加载完成" />
        ) : environmentVariableLibrary.isError ? (
          <Alert type="error" showIcon message="环境变量库加载失败" />
        ) : availableEnvironmentVariables.length === 0 && !environmentVariableLibrary.isLoading ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={(environmentVariableLibrary.data?.length ?? 0) === 0
              ? '变量库暂无可用变量'
              : '所有变量均已挂载'}
          />
        ) : (
          <Select
            aria-label="选择环境变量"
            showSearch
            allowClear
            loading={environmentVariableLibrary.isLoading}
            placeholder="按变量名或说明搜索"
            optionFilterProp="label"
            style={{ width: '100%' }}
            value={selectedEnvironmentVariableId}
            onChange={setSelectedEnvironmentVariableId}
            options={availableEnvironmentVariables.map(variable => ({
              value: variable.id,
              label: `${variable.name}${variable.description ? ` — ${variable.description}` : ''}`,
            }))}
          />
        )}
      </Modal>
    </div>
  );
}
