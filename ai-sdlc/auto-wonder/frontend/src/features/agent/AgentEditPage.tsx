import { useEffect, useMemo, useState } from 'react';
import { type FocusEvent, useRef, type KeyboardEvent } from 'react';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import {
  Alert, AutoComplete, Button, Card, Divider, Form, Input, Modal, Popconfirm, Result, Select,
  Space, Spin, Table, Tag, message,
} from 'antd';
import { ArrowLeftOutlined, SaveOutlined, SendOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  useAgent, useAgentVersion, useEditConfig, useSubmitForReview,
  useAddRepoPerm, useRemoveRepoPerm, useAddSkill, useRemoveSkill,
  useAddMemoryRef, useRemoveMemoryRef,
} from './hooks';
import { listRepos } from '@/features/repo/api';
import { listSkills } from '@/features/skill/api';
import { listMemories } from '@/features/memory/api';
import { listSdlcTemplates } from '@/features/sdlc/api';
import { AGENT_ROLE_CODE_OPTIONS, AGENT_ROLE_NAME_OPTIONS, getRoleCodeByName, getRoleNameByCode } from './constants';
import type { ColumnsType } from 'antd/es/table';
import type { EvolutionMode } from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { TextArea } = Input;

interface RepoPermRow {
  repoId: string;
  repoName: string;
  permLevel: string;
}

interface SkillRow {
  skillId: number;
  skillName: string;
  type: 'SKILL' | 'MCP' | 'PLUGIN';
  version?: number;
}

interface MemoryRow {
  memoryId: number;
  contentMd: string;
  source: string;
}

function extractList<T>(value: T[] | { list?: T[] } | undefined): T[] {
  if (!value) return [];
  return Array.isArray(value) ? value : (value.list ?? []);
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

export function AgentEditPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const agentId = Number(id);
  const [form] = Form.useForm();
  const businessBackgroundRef = useRef<TextAreaRef>(null);
  const accessCommand = useAccessCommand();

  const { data: agent, isLoading, isError } = useAgent(agentId);
  const latestVersionNo = agent?.latestVersionNo ?? 0;
  const { data: versionDetail } = useAgentVersion(agentId, latestVersionNo);

  const editConfig = useEditConfig();
  const submitForReview = useSubmitForReview();
  const addRepoPerm = useAddRepoPerm();
  const removeRepoPerm = useRemoveRepoPerm();
  const addSkillMut = useAddSkill();
  const removeSkillMut = useRemoveSkill();
  const addMemoryRef = useAddMemoryRef();
  const removeMemoryRef = useRemoveMemoryRef();

  // Local state for relation tables (bound to agent's current relations)
  const [repoPerms, setRepoPerms] = useState<RepoPermRow[]>([]);
  const [skills, setSkills] = useState<SkillRow[]>([]);
  const [memories, setMemories] = useState<MemoryRow[]>([]);
  const [saveFeedback, setSaveFeedback] = useState<{ message: string; description: string } | null>(null);

  // Selector state
  const [repoModalOpen, setRepoModalOpen] = useState(false);
  const [skillModalOpen, setSkillModalOpen] = useState(false);
  const [memoryModalOpen, setMemoryModalOpen] = useState(false);
  const [selectedRepoId, setSelectedRepoId] = useState<string | undefined>();
  const [selectedPermLevel, setSelectedPermLevel] = useState('READ');
  const [selectedSkillId, setSelectedSkillId] = useState<number | undefined>();
  const [selectedMemoryId, setSelectedMemoryId] = useState<number | undefined>();

  // Reference data — backend returns raw arrays; API types say PageResult but runtime is T[]
  const { data: reposRaw } = useQuery({ queryKey: ['repos', 1, 100], queryFn: () => listRepos({ page: 1, size: 100 }) });
  const { data: skillsRaw } = useQuery({ queryKey: ['skills', 1, 100], queryFn: () => listSkills({ page: 1, size: 100 }) });
  const { data: memoriesRaw } = useQuery({ queryKey: ['memories', 1, 100], queryFn: () => listMemories({ page: 1, size: 100 }) });
  const { data: sdlcsRaw } = useQuery({ queryKey: ['sdlcs', 1, 100], queryFn: () => listSdlcTemplates({ page: 1, size: 100 }) });
  // Safe extract: handle both PageResult and raw array
  const reposList = useMemo(() => extractList(reposRaw), [reposRaw]);
  const skillsList = useMemo(() => extractList(skillsRaw), [skillsRaw]);
  const memoriesList = useMemo(() => extractList(memoriesRaw), [memoriesRaw]);
  const sdlcsList = useMemo(() => extractList(sdlcsRaw), [sdlcsRaw]);

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

    setRepoPerms((versionDetail.repoPerms ?? []).map((perm) => {
      const repo = reposList.find((item) => item.id === perm.repoId);
      return {
        repoId: perm.repoId,
        repoName: repo?.name || `#${perm.repoId}`,
        permLevel: perm.permLevel,
      };
    }));

    setSkills((versionDetail.skills ?? []).map((skillRef) => {
      const skill = skillsList.find((item) => item.id === skillRef.skillId);
      return {
        skillId: skillRef.skillId,
        skillName: skill?.name || `#${skillRef.skillId}`,
        type: skill?.type || 'SKILL',
        version: skill?.version,
      };
    }));

    setMemories((versionDetail.memoryRefs ?? []).map((memoryRef) => {
      const memory = memoriesList.find((item) => item.id === memoryRef.memoryId);
      return {
        memoryId: memoryRef.memoryId,
        contentMd: memory?.contentMd || `#${memoryRef.memoryId}`,
        source: memoryRef.source,
      };
    }));
  }, [versionDetail, reposList, skillsList, memoriesList]);

  if (!agentId || isNaN(agentId)) return (
    <Result status="404" title="无效的 ID" extra={<Button onClick={() => navigate(-1)}>返回</Button>} />
  );
  if (isLoading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (isError || !agent) return (
    <Result status="error" title="加载失败" extra={<Button onClick={() => navigate(-1)}>返回</Button>} />
  );

  const handleSave = () => {
    accessCommand('READ_WRITE', '保存数字员工配置', async () => {
      try {
        const values = await form.validateFields();
        await editConfig.mutateAsync({ agentId, config: values });
        message.success('配置已保存为草稿');
        setSaveFeedback({
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
    accessCommand('READ_WRITE', '提交数字员工审核', async () => {
      try {
        const values = await form.validateFields();
        await editConfig.mutateAsync({ agentId, config: values });
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
    if (!selectedRepoId) return;
    const repo = reposList.find(r => r.id === selectedRepoId);
    accessCommand('READ_WRITE', '添加数字员工仓库', () => {
      addRepoPerm.mutate({ agentId, repoId: selectedRepoId, permLevel: selectedPermLevel }, {
        onSuccess: () => {
          setRepoPerms(prev => [...prev, { repoId: selectedRepoId, repoName: repo?.name || `#${selectedRepoId}`, permLevel: selectedPermLevel }]);
          setRepoModalOpen(false);
          setSelectedRepoId(undefined);
        },
        onError: (e) => message.error(errorMessage(e, '添加仓库失败')),
      });
    });
  };

  const handleRemoveRepo = (repoId: string) => {
    accessCommand('READ_WRITE', '移除数字员工仓库', () => {
      removeRepoPerm.mutate({ agentId, repoId }, {
        onSuccess: () => setRepoPerms(prev => prev.filter(r => r.repoId !== repoId)),
        onError: (e) => message.error(errorMessage(e, '移除仓库失败')),
      });
    });
  };

  const handleAddSkill = () => {
    if (!selectedSkillId) return;
    const skill = skillsList.find(s => s.id === selectedSkillId);
    accessCommand('READ_WRITE', '添加数字员工能力', () => {
      addSkillMut.mutate({ agentId, skillId: selectedSkillId }, {
        onSuccess: () => {
          setSkills(prev => [...prev, {
            skillId: selectedSkillId,
            skillName: skill?.name || `#${selectedSkillId}`,
            type: skill?.type || 'SKILL',
            version: skill?.version,
          }]);
          setSkillModalOpen(false);
          setSelectedSkillId(undefined);
        },
        onError: (e) => message.error(errorMessage(e, '添加技能失败')),
      });
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
    if (!selectedMemoryId) return;
    const mem = memoriesList.find(m => m.id === selectedMemoryId);
    accessCommand('READ_WRITE', '导入数字员工记忆', () => {
      addMemoryRef.mutate({ agentId, memoryId: selectedMemoryId, source: 'ORG' }, {
        onSuccess: () => {
          setMemories(prev => [...prev, { memoryId: selectedMemoryId, contentMd: mem?.contentMd || '', source: 'ORG' }]);
          setMemoryModalOpen(false);
          setSelectedMemoryId(undefined);
        },
        onError: (e) => message.error(errorMessage(e, '导入记忆失败')),
      });
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

  const repoColumns: ColumnsType<RepoPermRow> = [
    { title: '仓库', dataIndex: 'repoName' },
    { title: '权限', dataIndex: 'permLevel', width: 100, render: (v: string) => <Tag>{v}</Tag> },
    {
      title: '操作', width: 80,
      render: (_: unknown, r: RepoPermRow) => (
        <Popconfirm title="确认移除?" onConfirm={() => handleRemoveRepo(r.repoId)}>
          <Button type="link" size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
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
    { title: '内容', dataIndex: 'contentMd', ellipsis: true },
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
            <Button icon={<SaveOutlined />} onClick={handleSave} loading={editConfig.isPending}>
              保存草稿
            </Button>
            <Button type="primary" icon={<SendOutlined />} onClick={handleSubmit}
              loading={submitForReview.isPending}>
              提交审核
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" style={{ maxWidth: 800 }}>
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
          <Form.Item label="SDLC 模版" name="sdlcId">
            <Select allowClear placeholder="选择关联的 SDLC 模版"
              options={sdlcsList.map(s => ({ value: s.id, label: `${s.name} (${s.status})` })) || []}
            />
          </Form.Item>
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
        extra={<Button size="small" icon={<PlusOutlined />}
          onClick={() => accessCommand('READ_WRITE', '添加数字员工仓库', () => setRepoModalOpen(true))}>
          添加仓库
        </Button>}
      >
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

      {/* Add Repo Modal */}
      <Modal title="添加仓库权限" open={repoModalOpen} onOk={handleAddRepo} onCancel={() => setRepoModalOpen(false)}
        okButtonProps={{ disabled: !selectedRepoId }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Select placeholder="选择仓库" style={{ width: '100%' }} value={selectedRepoId}
            onChange={setSelectedRepoId} showSearch optionFilterProp="label"
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
        </Space>
      </Modal>

      {/* Add Capability Modal */}
      <Modal title="添加能力" open={skillModalOpen} onOk={handleAddSkill} onCancel={() => setSkillModalOpen(false)}
        okButtonProps={{ disabled: !selectedSkillId }}>
        <Select placeholder="选择 Skill、MCP 或 Plugin" style={{ width: '100%' }} value={selectedSkillId}
          onChange={setSelectedSkillId} showSearch optionFilterProp="label"
          options={skillsList.filter(s => !skills.some(sk => sk.skillId === s.id))
            .map(s => ({ value: s.id, label: `${s.name} (${s.type})` })) || []}
        />
      </Modal>

      {/* Add Memory Modal */}
      <Modal title="导入记忆" open={memoryModalOpen} onOk={handleAddMemory} onCancel={() => setMemoryModalOpen(false)}
        okButtonProps={{ disabled: !selectedMemoryId }}>
        <Select placeholder="选择记忆" style={{ width: '100%' }} value={selectedMemoryId}
          onChange={setSelectedMemoryId} showSearch optionFilterProp="label"
          options={memoriesList.filter(m => !memories.some(me => me.memoryId === m.id))
            .map(m => ({ value: m.id, label: m.contentMd.slice(0, 60) })) || []}
        />
      </Modal>
    </div>
  );
}
