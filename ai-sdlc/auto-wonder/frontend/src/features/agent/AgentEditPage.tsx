import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { PageHeading } from '@/shared/ui/PageHeading';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { useEffect, useMemo, useState } from 'react';
import { type FocusEvent, useRef, type KeyboardEvent } from 'react';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import {
  Alert, AutoComplete, Button, Card, Divider, Form, Input, Modal, Popconfirm, Result, Select,
  Empty, Space, Spin, Tag, message} from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { SaveOutlined, SendOutlined, DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  useAgent, useAgentVersion, useEditConfig, useSubmitForReview, useUpdateAgent,
  useAddRepoPerm, useRemoveRepoPerm, useAddSkill, useRemoveSkill,
  useAddEnvironmentVariableRef, useRemoveEnvironmentVariableRef,
} from './hooks';
import { listRepos } from '@/features/repo/api';
import { listSkills } from '@/features/skill/api';
import { listSdlcTemplates } from '@/features/sdlc/api';
import { getAgentRoleCodeOptions, getAgentRoleNameOptions, getEvolutionModeOptions, getRoleCodeByName, getRoleNameByCode } from './constants';
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
  const { t } = useTranslation('agent');
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
  useLocalizedValidation(form, Boolean(agentId) && !Number.isNaN(agentId) && !isLoading && !isError && Boolean(agent));
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
  const addEnvironmentVariableRef = useAddEnvironmentVariableRef();
  const removeEnvironmentVariableRef = useRemoveEnvironmentVariableRef();
  const environmentVariableLibrary = useEnvironmentVariables(canManageEnvironmentVariables);

  // Local state for relation tables (bound to agent's current relations)
  const [repoPerms, setRepoPerms] = useState<RepoPermRow[]>([]);
  const [skills, setSkills] = useState<SkillRow[]>([]);
  const [saveFeedback, setSaveFeedback] = useState<boolean | null>(null);

  // Selector state
  const [repoModalOpen, setRepoModalOpen] = useState(false);
  const [skillModalOpen, setSkillModalOpen] = useState(false);
  const [selectedRepoIds, setSelectedRepoIds] = useState<number[]>([]);
  const [selectedPermLevel, setSelectedPermLevel] = useState('READ');
  const [selectedBranchPatterns, setSelectedBranchPatterns] = useState<string[]>([]);
  const [editingRepoPerm, setEditingRepoPerm] = useState<RepoPermRow | null>(null);
  const [editingPermLevel, setEditingPermLevel] = useState('READ');
  const [editingBranchPatterns, setEditingBranchPatterns] = useState<string[]>([]);
  const [selectedSkillIds, setSelectedSkillIds] = useState<number[]>([]);
  const [environmentModalOpen, setEnvironmentModalOpen] = useState(false);
  const [selectedEnvironmentVariableId, setSelectedEnvironmentVariableId] = useState<number>();
  const [environmentBindingError, setEnvironmentBindingError] = useState<{ error: unknown }>();
  const [environmentMountError, setEnvironmentMountError] = useState<{ error: unknown }>();
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
  const { data: sdlcsRaw } = useQuery({ queryKey: ['sdlcs', 1, 100], queryFn: () => listSdlcTemplates({ page: 1, size: 100 }) });
  // Safe extract: handle both PageResult and raw array
  const reposList = useMemo(() => extractList(reposRaw), [reposRaw]);
  const skillsList = useMemo(() => extractList(skillsRaw), [skillsRaw]);
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
        // 本地技能列表只取第一页，优先用服务端解析出的名称，避免已绑定能力退化成 #id
        skillName: skillRef.skillName || skill?.name || `#${skillRef.skillId}`,
        type: skill?.type || 'SKILL',
        version: skill?.version,
      };
    }), item => item.skillId));

  }, [versionDetail, reposList, skillsList]);

  if (!agentId || isNaN(agentId)) return (
    <Result status="404" title={t('errors.invalidId')} extra={<Button onClick={() => navigate(-1)}>{t('actions.back')}</Button>} />
  );
  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (isError || !agent) return (
    <Result status="error" title={t('errors.load')} extra={<Button onClick={() => navigate(-1)}>{t('actions.back')}</Button>} />
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
      message.warning(i18n.t('edit.mountPendingSave', { ns: 'agent' }));
      return;
    }
    accessCommand('READ_WRITE', t('actions.saveConfig'), async () => {
      try {
        const values: AgentFormValues = await form.validateFields();
        const agentPatched = await persistForm(values);
        message.success(i18n.t(agentPatched ? 'edit.nameConfigSaved' : 'edit.configSaved', { ns: 'agent' }));
        setSaveFeedback(agentPatched);
      } catch (e: unknown) {
        if (e instanceof Error && e.message) message.error(e.message);
      }
    });
  };

  const handleSubmit = () => {
    if (hasPendingEnvironmentServerOperation()) {
      message.warning(i18n.t('edit.mountPendingSubmit', { ns: 'agent' }));
      return;
    }
    accessCommand('READ_WRITE', t('actions.submitReview'), async () => {
      try {
        const values: AgentFormValues = await form.validateFields();
        await persistForm(values);
        await submitForReview.mutateAsync(agentId);
        message.success(i18n.t('edit.submitted', { ns: 'agent' }));
        navigate(`/agents/${agentId}`);
      } catch (e: unknown) {
        const msg = errorMessage(e, i18n.t('errors.submit', { ns: 'agent' }));
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
    accessCommand('READ_WRITE', t('actions.addRepo'), async () => {
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
        message.error(errorMessage(e, i18n.t('errors.addRepo', { ns: 'agent' })));
      }
    });
  };

  const openEditRepoPerm = (repo: RepoPermRow) => {
    accessCommand('READ_WRITE', t('actions.editRepo'), () => {
      setEditingRepoPerm(repo);
      setEditingPermLevel(repo.permLevel);
      setEditingBranchPatterns(repo.allowedBranchPatterns);
    });
  };

  const handleEditRepoPerm = () => {
    if (!editingRepoPerm) return;
    accessCommand('READ_WRITE', t('actions.editRepo'), async () => {
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
        message.error(errorMessage(e, i18n.t('errors.editRepo', { ns: 'agent' })));
      }
    });
  };

  const handleRemoveRepo = (repoId: number) => {
    accessCommand('READ_WRITE', t('actions.removeRepo'), () => {
      removeRepoPerm.mutate({ agentId, repoId }, {
        onSuccess: () => setRepoPerms(prev => prev.filter(r => r.repoId !== repoId)),
        onError: (e) => message.error(errorMessage(e, i18n.t('errors.removeRepo', { ns: 'agent' }))),
      });
    });
  };

  const handleAddSkill = () => {
    if (selectedSkillIds.length === 0) return;
    accessCommand('READ_WRITE', t('actions.addCapability'), async () => {
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
        message.error(errorMessage(e, i18n.t('errors.addCapability', { ns: 'agent' })));
      }
    });
  };

  const handleRemoveSkill = (skillId: number) => {
    accessCommand('READ_WRITE', t('actions.removeCapability'), () => {
      removeSkillMut.mutate({ agentId, skillId }, {
        onSuccess: () => setSkills(prev => prev.filter(s => s.skillId !== skillId)),
        onError: (e) => message.error(errorMessage(e, i18n.t('errors.removeCapability', { ns: 'agent' }))),
      });
    });
  };

  const handleAddEnvironmentVariable = () => {
    if (!environmentVersionReady || !selectedEnvironmentVariableId || environmentOperationPending) return;
    const environmentVariableId = selectedEnvironmentVariableId;
    const operationKey = `mount:${environmentVariableId}`;
    if (environmentOperationTokens.current.has(operationKey)) return;
    accessCommand('READ_WRITE', t('actions.mountVariable'), async () => {
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
          setEnvironmentMountError({ error: e });
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
    accessCommand('READ_WRITE', t('actions.unmountVariable'), async () => {
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
          setEnvironmentBindingError({ error: e });
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
  const permissionLabels = new Map<string, string>([
    ['READ', t('permissions.READ')], ['WRITE', t('permissions.WRITE')], ['ADMIN', t('permissions.ADMIN')],
  ]);
  const capabilityTypeLabels = new Map<string, string>([
    ['SKILL', t('capabilityTypes.SKILL')], ['MCP', t('capabilityTypes.MCP')],
    ['PLUGIN', t('capabilityTypes.PLUGIN')], ['HOOK', t('capabilityTypes.HOOK')],
  ]);
  const sdlcStatusLabels = new Map<string, string>([
    ['DRAFT', t('sdlcStatus.DRAFT')], ['ENABLED', t('sdlcStatus.ENABLED')], ['DISABLED', t('sdlcStatus.DISABLED')],
  ]);
  const repoColumns: ColumnsType<RepoPermRow> = [
    { title: t('fields.repository'), dataIndex: 'repoName' },
    { title: t('fields.permission'), dataIndex: 'permLevel', width: 100, render: (v: string) => <Tag>{permissionLabels.get(v) || v}</Tag> },
    ...(isPlatform ? [] : ([
      {
        title: t('fields.commitBranches'), dataIndex: 'allowedBranchPatterns',
        render: (patterns: string[]) => patterns.length === 0
          ? <span style={{ color: 'var(--aw-muted)' }}>{t('repository.unrestricted')}</span>
          : <Space size={[0, 4]} wrap>{patterns.map(pattern => <Tag key={pattern}>{pattern}</Tag>)}</Space>,
      },
      {
        title: t('fields.actions'), width: 120,
        render: (_: unknown, r: RepoPermRow) => (
          <Space size={0}>
            <Button type="link" size="small" aria-label={t('repository.editBranchesLabel', { name: r.repoName })}
              icon={<EditOutlined aria-hidden="true" />} onClick={() => openEditRepoPerm(r)} />
            <Popconfirm title={t('actions.confirmRemove')} onConfirm={() => handleRemoveRepo(r.repoId)}>
              <Button type="link" size="small" danger aria-label={t('repository.removeLabel', { name: r.repoName })} icon={<DeleteOutlined aria-hidden="true" />} />
            </Popconfirm>
          </Space>
        ),
      },
    ] as ColumnsType<RepoPermRow>)),
  ];

  const skillColumns: ColumnsType<SkillRow> = [
    { title: t('fields.capability'), dataIndex: 'skillName' },
    { title: t('fields.type'), dataIndex: 'type', width: 110, render: (value: string) => <Tag>{capabilityTypeLabels.get(value) || value}</Tag> },
    { title: t('fields.version'), dataIndex: 'version', width: 80, render: (value?: number) => value ?? '-' },
    {
      title: t('fields.actions'), width: 80,
      render: (_: unknown, r: SkillRow) => (
        <Popconfirm title={t('actions.confirmRemove')} onConfirm={() => handleRemoveSkill(r.skillId)}>
          <Button type="link" size="small" danger aria-label={t('capability.removeLabel', { name: r.skillName })} icon={<DeleteOutlined aria-hidden="true" />} />
        </Popconfirm>
      ),
    },
  ];

  const environmentVariableColumns: ColumnsType<AgentEnvironmentVariableRef> = [
    { title: t('fields.variableName'), dataIndex: 'name', align: 'left', width: '36%' },
    {
      title: t('fields.description'),
      dataIndex: 'description', align: 'left',
      render: (description: string | null) => description || <span style={{ color: 'var(--aw-muted)' }}>{t('environment.noDescription')}</span>,
    },
    ...(canManageEnvironmentVariables ? ([{
      title: t('fields.actions'),
      width: 80,
      render: (_: unknown, variable: AgentEnvironmentVariableRef) => (
        <Popconfirm
          title={t('environment.confirmUnbind', { name: variable.name })}
          okText={t('actions.confirmUnbind')}
          cancelText={t('actions.cancel')}
          onConfirm={() => handleRemoveEnvironmentVariable(variable)}
        >
          <Button
            type="link"
            size="small"
            danger
            aria-label={t('environment.unbindLabel', { name: variable.name })}
            icon={<DeleteOutlined aria-hidden="true" />}
            disabled={environmentOperationPending}
          />
        </Popconfirm>
      ),
    }] as ColumnsType<AgentEnvironmentVariableRef>) : []),
  ];
  return (
    <div>

      {saveFeedback !== null && (
        <Alert
          showIcon
          type="success"
          message={t(saveFeedback ? 'edit.saved' : 'edit.draftSaved')}
          description={t(saveFeedback ? 'edit.savedHint' : 'edit.draftSavedHint')}
          style={{ marginBottom: 16 }}
        />
      )}

      <Card className="aw-content-card" title={<PageHeading title={<span className="aw-detail-title"><PageBackButton to={`/agents/${agentId}`} label={t('actions.backDetail')} /><span>{t('edit.title', { name: agent.name })}</span></span>} />}
        extra={
          <Space>
            <Button icon={<SaveOutlined aria-hidden="true" />} onClick={handleSave}
              loading={editConfig.isPending || updateAgent.isPending}
              disabled={environmentServerOperationPending}>
              {t('actions.saveDraft')}
            </Button>
            <Button type="primary" icon={<SendOutlined aria-hidden="true" />} onClick={handleSubmit}
              loading={submitForReview.isPending}
              disabled={environmentServerOperationPending}>
              {t('actions.submitButton')}
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical">
          <Form.Item
            label={t('fields.agentName')}
            name="name"
            rules={[{ required: true, message: t('validation.agentName') }]}
            extra={isPlatform
              ? t('edit.platformName')
              : t('edit.nameHint')}
          >
            <Input placeholder={t('placeholders.agentName')} maxLength={64} disabled={isPlatform} />
          </Form.Item>
          <Form.Item
            label={t('fields.avatar')}
            name="avatarUrl"
            extra={isPlatform
              ? t('edit.platformAvatar')
              : t('edit.avatarHint')}
          >
            <Input placeholder="https://..." allowClear disabled={isPlatform} />
          </Form.Item>
          <Form.Item label={t('fields.roleName')} name="roleName" rules={[{ required: true, message: t('validation.roleName') }]}>
            <AutoComplete
              options={getAgentRoleNameOptions()}
              allowClear
              placeholder={t('placeholders.roleName')}
              onSelect={handleRoleNameSelect}
            />
          </Form.Item>
          <Form.Item label={t('fields.roleCode')} name="roleCode" rules={[{ required: true, message: t('validation.roleCode') }]}>
            <AutoComplete
              options={getAgentRoleCodeOptions()}
              allowClear
              placeholder={t('placeholders.roleCode')}
              onSelect={handleRoleCodeSelect}
              onBlur={handleRoleCodeBlur}
              onKeyDown={handleRoleCodeKeyDown}
            />
          </Form.Item>
          <Form.Item label="SOUL.md" name={isPlatform ? undefined : "businessBackground"}>
            <TextArea
              readOnly={isPlatform}
              value={isPlatform ? agent?.officialConfiguration?.businessBackground ?? versionDetail?.businessBackground : undefined}
              ref={businessBackgroundRef}
              rows={4}
              placeholder={t('placeholders.background')}
            />
          </Form.Item>
          <Form.Item label="AGENT.md" name={isPlatform ? undefined : "responsibilities"}>
            <TextArea readOnly={isPlatform} value={isPlatform ? agent?.officialConfiguration?.responsibilities ?? versionDetail?.responsibilities : undefined} rows={4} placeholder={t('placeholders.responsibilities')} />
          </Form.Item>
          {!isPlatform && (
            <Form.Item label={t('fields.sdlc')} name="sdlcId">
              <Select allowClear placeholder={t('placeholders.sdlc')}
                options={sdlcsList.map(s => ({ value: s.id, label: t('namedLabel', { name: s.name, label: sdlcStatusLabels.get(s.status) || s.status }) })) || []}
              />
            </Form.Item>
          )}
          <Form.Item
            label={t('fields.evolution')}
            name="evolutionMode"
            tooltip={t('evolution.tooltip')}
          >
            <Select
              aria-label={t('fields.evolution')}
              options={getEvolutionModeOptions()}
              optionRender={(option) => {
                const descriptions: Record<string, string> = {
                  MANUAL: t('evolutionDescription.MANUAL'),
                  ASSISTED: t('evolutionDescription.ASSISTED'),
                  AUTO_PROPOSAL: t('evolutionDescription.AUTO_PROPOSAL'),
                };
                return (
                  <Space direction="vertical" size={0}>
                    <span>{option.label}</span>
                    <span style={{ fontSize: 12, color: 'var(--aw-muted)' }}>{descriptions[String(option.value)]}</span>
                  </Space>
                );
              }}
            />
          </Form.Item>
        </Form>
      </Card>

      <Divider />

      {/* Repo Permissions */}
      <Card title={t('repository.title')} style={{ marginTop: 16 }}
        extra={isPlatform ? null : (
          <Button size="small" icon={<PlusOutlined aria-hidden="true" />}
            onClick={() => accessCommand('READ_WRITE', t('actions.addRepo'), () => setRepoModalOpen(true))}>
            {t('repository.add')}
          </Button>
        )}
      >
        {isPlatform && (
          <Alert
            type="info"
            showIcon
            message={t('repository.platformTitle')}
            description={t('repository.platformHint')}
            style={{ marginBottom: 12 }}
          />
        )}
        <Table rowKey="repoId" columns={repoColumns} dataSource={repoPerms} pagination={false} size="small" />
      </Card>

      {/* Capabilities */}
      <Card title={t('capability.title')} style={{ marginTop: 16 }}
        extra={<Button size="small" icon={<PlusOutlined aria-hidden="true" />}
          onClick={() => accessCommand('READ_WRITE', t('actions.addCapability'), () => setSkillModalOpen(true))}>
          {t('capability.add')}
        </Button>}
      >
        <Alert
          type="info"
          showIcon
          message={t('capability.builtIn')}
          description={t('capability.builtInHint')}
          style={{ marginBottom: 12 }}
        />
        <Table rowKey="skillId" columns={skillColumns} dataSource={skills} pagination={false} size="small" />
      </Card>

      {/* Environment variables are metadata-only here; saved values are never returned to the browser. */}
      <Card title={t('environment.title')} style={{ marginTop: 16 }}
        extra={canManageEnvironmentVariables ? (
          <Button
            size="small"
            icon={<PlusOutlined aria-hidden="true" />}
            onClick={() => {
              environmentOperationTokens.current.clear();
              setEnvironmentBindingError(undefined);
              setEnvironmentMountError(undefined);
              setEnvironmentModalOpen(true);
            }}
            disabled={environmentOperationPending || !environmentVersionReady}
          >
            {t('environment.mount')}
          </Button>
        ) : null}
      >
        <Alert
          type="info"
          showIcon
          message={t('environment.draftHint')}
          description={t('environment.valueHint')}
          style={{ marginBottom: 12 }}
        />
        {environmentBindingError && (
          <Alert
            type="error"
            showIcon
            message={errorMessage(environmentBindingError.error, t('errors.unmount'))}
            closable
            onClose={() => setEnvironmentBindingError(undefined)}
            style={{ marginBottom: 12 }}
          />
        )}
        {isVersionDetailLoading ? (
          <Space>
            <Spin size="small" />
            <span>{t('environment.loading')}</span>
          </Space>
        ) : isVersionDetailError ? (
          <Alert
            type="error"
            showIcon
            message={t('errors.bindingsLoad')}
            description={errorMessage(versionDetailError, t('errors.versionLoad'))}
            action={(
              <Button size="small" loading={isVersionDetailFetching} onClick={() => refetchVersionDetail()}>
                {t('actions.retry')}
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
            locale={{ emptyText: t('environment.empty') }}
          />
        )}
      </Card>

      {/* Add Repo Modal */}
      <Modal title={t('repository.addPermission')} open={repoModalOpen} onOk={handleAddRepo} onCancel={() => setRepoModalOpen(false)}
        okButtonProps={{ disabled: selectedRepoIds.length === 0 }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Select mode="multiple" placeholder={t('placeholders.repositories')} style={{ width: '100%' }} value={selectedRepoIds}
            onChange={setSelectedRepoIds} showSearch optionFilterProp="label"
            options={reposList.filter(r => !repoPerms.some(p => p.repoId === r.id))
              .map(r => ({ value: r.id, label: r.name })) || []}
          />
          <Select value={selectedPermLevel} onChange={setSelectedPermLevel} style={{ width: '100%' }}
            options={[
              { value: 'READ', label: t('permissions.READ') },
              { value: 'WRITE', label: t('permissions.WRITE') },
              { value: 'ADMIN', label: t('permissions.ADMIN') },
            ]}
          />
          <Select mode="tags" aria-label={t('fields.allowedBranches')} value={selectedBranchPatterns}
            onChange={setSelectedBranchPatterns} tokenSeparators={[',']} style={{ width: '100%' }}
            placeholder={t('placeholders.branches')} maxCount={32}
          />
        </Space>
      </Modal>

      <Modal title={t('repository.editPermission')} open={editingRepoPerm !== null} onOk={handleEditRepoPerm}
        onCancel={() => setEditingRepoPerm(null)}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input value={editingRepoPerm?.repoName} disabled />
          <Select value={editingPermLevel} onChange={setEditingPermLevel} style={{ width: '100%' }}
            options={[
              { value: 'READ', label: t('permissions.READ') },
              { value: 'WRITE', label: t('permissions.WRITE') },
              { value: 'ADMIN', label: t('permissions.ADMIN') },
            ]}
          />
          <Select mode="tags" aria-label={t('fields.allowedBranches')} value={editingBranchPatterns}
            onChange={setEditingBranchPatterns} tokenSeparators={[',']} style={{ width: '100%' }}
            placeholder={t('placeholders.editBranches')} maxCount={32}
          />
        </Space>
      </Modal>

      {/* Add Capability Modal */}
      <Modal title={t('capability.add')} open={skillModalOpen} onOk={handleAddSkill} onCancel={() => setSkillModalOpen(false)}
        okButtonProps={{ disabled: selectedSkillIds.length === 0 }}>
        <Select mode="multiple" placeholder={t('placeholders.capabilities')} style={{ width: '100%' }} value={selectedSkillIds}
          onChange={setSelectedSkillIds} showSearch optionFilterProp="label"
          options={skillsList.filter(s => !skills.some(sk => sk.skillId === s.id))
            .map(s => ({ value: s.id, label: t('namedLabel', { name: s.name, label: capabilityTypeLabels.get(s.type) || s.type }) })) || []}
        />
      </Modal>

      <Modal
        title={t('environment.mount')}
        open={environmentModalOpen && canManageEnvironmentVariables}
        okText={t('actions.mount')}
        cancelText={t('actions.cancel')}
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
            message={errorMessage(environmentMountError.error, t('errors.mount'))}
            style={{ marginBottom: 12 }}
          />
        )}
        {!environmentVersionReady ? (
          <Alert type="warning" showIcon message={t('environment.waitVersion')} />
        ) : environmentVariableLibrary.isError ? (
          <Alert type="error" showIcon message={t('errors.libraryLoad')} />
        ) : availableEnvironmentVariables.length === 0 && !environmentVariableLibrary.isLoading ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={(environmentVariableLibrary.data?.length ?? 0) === 0
              ? t('environment.libraryEmpty')
              : t('environment.allMounted')}
          />
        ) : (
          <Select
            aria-label={t('fields.selectVariable')}
            showSearch
            allowClear
            loading={environmentVariableLibrary.isLoading}
            placeholder={t('placeholders.variables')}
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
