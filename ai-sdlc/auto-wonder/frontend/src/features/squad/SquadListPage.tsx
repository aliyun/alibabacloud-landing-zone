import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Button, Space, Modal, Form, Input, Popconfirm, message, Tag, List, Select, Spin, Empty, Switch,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, UserAddOutlined, EyeOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listAllSquads, createSquad, updateSquad, deleteSquad, getSquad,
  getSquadMembers, addSquadMember, removeSquadMember,
} from './api';
import { listAgents } from '@/features/agent/api';
import type { Squad, SquadMember } from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import './SquadListPage.css';

export function SquadListPage() {
  const queryClient = useQueryClient();
  const accessCommand = useAccessCommand();

  const [formOpen, setFormOpen] = useState(false);
  const [editingSquad, setEditingSquad] = useState<Squad | null>(null);
  const [form] = Form.useForm();

  const [membersOpen, setMembersOpen] = useState(false);
  const [membersSquadId, setMembersSquadId] = useState<number | null>(null);
  const [addAgentId, setAddAgentId] = useState<number | undefined>(undefined);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailSquad, setDetailSquad] = useState<Squad | null>(null);
  const [searchParams, setSearchParams] = useSearchParams();

  const { data, isLoading } = useQuery({
    queryKey: ['squads'],
    queryFn: () => listAllSquads(),
  });

  // Squad tags on the agent / SDLC / executor pages link here as /agents?tab=squads&squadId=<id>; there is
  // no per-squad route, so the param drives this Modal. The param is consumed up front, otherwise a
  // stale one reopens the Modal every time the list refreshes.
  const [deepLinkSquadId, setDeepLinkSquadId] = useState<number | null>(null);

  useEffect(() => {
    const raw = searchParams.get('squadId');
    if (raw === null) return;
    const consumed = new URLSearchParams(searchParams);
    consumed.delete('squadId');
    setSearchParams(consumed, { replace: true });
    const parsed = Number(raw);
    if (Number.isInteger(parsed) && parsed > 0) {
      setDeepLinkSquadId(parsed);
    }
  }, [searchParams, setSearchParams]);

  // Keyed like detailSquadFull below so the deep link and the Modal address the same cache entry.
  const { data: deepLinkSquad, isError: deepLinkFailed } = useQuery({
    queryKey: ['squad-detail', deepLinkSquadId],
    queryFn: () => getSquad(deepLinkSquadId!),
    enabled: deepLinkSquadId !== null,
  });

  useEffect(() => {
    if (deepLinkSquadId === null) return;
    if (deepLinkSquad) {
      setDetailSquad(deepLinkSquad);
      setDetailOpen(true);
      setDeepLinkSquadId(null);
      return;
    }
    if (deepLinkFailed) {
      message.warning(`小队 #${deepLinkSquadId} 不存在或当前账号无权访问`);
      setDeepLinkSquadId(null);
    }
  }, [deepLinkSquadId, deepLinkSquad, deepLinkFailed]);

  const { data: members = [], isLoading: membersLoading } = useQuery({
    queryKey: ['squad-members', membersSquadId],
    queryFn: () => getSquadMembers(membersSquadId!),
    enabled: !!membersSquadId,
  });

  const { data: detailMembers = [], isLoading: detailLoading } = useQuery({
    queryKey: ['squad-detail-members', detailSquad?.id],
    queryFn: () => getSquadMembers(detailSquad!.id),
    enabled: detailOpen && !!detailSquad,
  });

  // Only GET /api/squads/{id} derives the SDLC flows and executors the squad owns; the list rows omit them.
  const { data: detailSquadFull } = useQuery({
    queryKey: ['squad-detail', detailSquad?.id],
    queryFn: () => getSquad(detailSquad!.id),
    enabled: detailOpen && !!detailSquad,
  });

  const { data: agentsData } = useQuery({
    queryKey: ['agents-for-squad'],
    queryFn: () => listAgents({ page: 1, size: 100 }),
    enabled: membersOpen,
  });

  const invalidateSquads = () => queryClient.invalidateQueries({ queryKey: ['squads'] });
  const invalidateMembers = () => queryClient.invalidateQueries({ queryKey: ['squad-members', membersSquadId] });

  const createMut = useMutation({
    mutationFn: createSquad,
    onSuccess: () => { invalidateSquads(); setFormOpen(false); form.resetFields(); message.success('创建成功'); },
  });

  const updateMut = useMutation({
    mutationFn: ({ id, data: d }: {
      id: number;
      data: { name?: string; description?: string; ownerId?: number | null; debugLogEnabled?: boolean };
    }) => updateSquad(id, d),
    onSuccess: () => { invalidateSquads(); setFormOpen(false); setEditingSquad(null); form.resetFields(); message.success('已保存'); },
  });

  const deleteMut = useMutation({
    mutationFn: deleteSquad,
    onSuccess: () => { invalidateSquads(); message.success('已删除'); },
  });

  const addMemberMut = useMutation({
    mutationFn: (agentId: number) => addSquadMember(membersSquadId!, agentId),
    onSuccess: () => { invalidateMembers(); invalidateSquads(); setAddAgentId(undefined); message.success('已添加'); },
  });

  const removeMemberMut = useMutation({
    mutationFn: (agentId: number) => removeSquadMember(membersSquadId!, agentId),
    onSuccess: () => { invalidateMembers(); invalidateSquads(); message.success('已移除'); },
  });

  const openCreate = () => {
    accessCommand('READ_WRITE', '新建小队', () => {
      setEditingSquad(null);
      form.resetFields();
      setFormOpen(true);
    });
  };

  const openEdit = (squad: Squad) => {
    accessCommand('READ_WRITE', '编辑小队', () => {
      setEditingSquad(squad);
      form.setFieldsValue({
        name: squad.name,
        description: squad.description,
        debugLogEnabled: !!squad.debugLogEnabled,
      });
      setFormOpen(true);
    });
  };

  const openMembers = (squad: Squad) => {
    accessCommand('READ_WRITE', '管理小队成员', () => {
      setMembersSquadId(squad.id);
      setMembersOpen(true);
    });
  };

  const openDetail = (squad: Squad) => {
    setDetailSquad(squad);
    setDetailOpen(true);
  };

  const handleFormSubmit = async () => {
    const values = await form.validateFields();
    accessCommand('READ_WRITE', editingSquad ? '编辑小队' : '新建小队', () => {
      if (editingSquad) {
        // 后端 update 混合 PUT/PATCH 语义：name/description/owner_id 无条件覆盖，
        // 只有 debug_log_enabled 走 COALESCE。必须整对象提交，否则切开关会把
        // owner_id 抹成 null（name 是非空列，漏发会直接 500）。
        updateMut.mutate({
          id: editingSquad.id,
          data: {
            name: values.name ?? editingSquad.name,
            description: values.description ?? editingSquad.description,
            ownerId: editingSquad.ownerId ?? null,
            debugLogEnabled: !!values.debugLogEnabled,
          },
        });
      } else {
        createMut.mutate(values);
      }
    });
  };

  const availableAgents = (agentsData || []).filter(
    (agent) => !members.some((member) => member.agentId === agent.id),
  );
  const roleStats = buildRoleStats(detailMembers);
  const sdlcGroups = buildSdlcGroups(detailMembers);
  const detailExecutors = detailSquadFull?.executors ?? [];
  const detailSdlcs = detailSquadFull?.sdlcs ?? [];
  const squads = data ?? [];
  const memberTotal = squads.reduce((sum, squad) => sum + (squad.memberCount ?? 0), 0);
  const executorOnlineTotal = squads.reduce((sum, squad) => sum + (squad.executorOnlineCount ?? 0), 0);
  const sdlcTotal = squads.reduce((sum, squad) => sum + (squad.sdlcCount ?? 0), 0);

  return (
    <>
      <section className="squad-card-page">
        <div className="squad-list-toolbar">
          <div className="squad-summary-strip">
            <SummaryPill label="小队总数" value={squads.length} />
            <SummaryPill label="数字员工总数" value={memberTotal} />
            <SummaryPill label="执行器在线" value={executorOnlineTotal} />
            <SummaryPill label="关联 SDLC" value={sdlcTotal} />
          </div>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建小队</Button>
        </div>

        <Spin spinning={isLoading}>
          {squads.length === 0 && !isLoading ? (
            <Empty description="暂无小队" />
          ) : (
            <div className="squad-card-grid">
              {squads.map((squad) => (
                <article key={squad.id} className="squad-summary-card">
                  <div className="squad-card-topline" />
                  <div className="squad-card-main">
                    <div className="squad-team-avatar">
                      {(squad.name || '?').slice(0, 2)}
                    </div>
                    <div className="squad-card-title-area">
                      <div className="squad-card-title">
                        {squad.name}
                        {squad.debugLogEnabled ? (
                          <Tag color="orange" style={{ marginLeft: 6 }}>Debug</Tag>
                        ) : null}
                      </div>
                      <div className="squad-card-description">{squad.description || '暂无描述'}</div>
                    </div>
                  </div>

                  <div className="squad-metric-grid">
                    <SquadMetric label={`${squad.memberCount ?? 0} 个数字员工`} />
                    <SquadMetric label={`${squad.roleCount ?? 0} 类角色`} />
                    <SquadMetric label={executorText(squad)} active={(squad.executorOnlineCount ?? 0) > 0} />
                    <SquadMetric label={sdlcText(squad)} />
                  </div>

                  <div className="squad-card-footer">
                    <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => openDetail(squad)}>详情</Button>
                    <Button type="link" size="small" icon={<UserAddOutlined />} onClick={() => openMembers(squad)}>成员</Button>
                    <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(squad)}>编辑</Button>
                    <Popconfirm title="确认删除该小队？" onConfirm={() => accessCommand(
                      'READ_WRITE',
                      '删除小队',
                      () => deleteMut.mutate(squad.id),
                    )}>
                      <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
                    </Popconfirm>
                  </div>
                </article>
              ))}
            </div>
          )}
        </Spin>
      </section>

      <Modal
        title={editingSquad ? '编辑小队' : '新建小队'}
        open={formOpen}
        onOk={handleFormSubmit}
        onCancel={() => { setFormOpen(false); setEditingSquad(null); }}
        confirmLoading={createMut.isPending || updateMut.isPending}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="小队名称" rules={[{ required: true, message: '请输入小队名称' }]}>
            <Input placeholder="如: 前端开发小队" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} placeholder="描述该小队的业务方向和职责" />
          </Form.Item>
          {editingSquad ? (
            <Form.Item
              name="debugLogEnabled"
              label="Debug 日志收集"
              valuePropName="checked"
              extra="开启后，该小队数字员工每轮执行的全量日志将自动压缩上传，保留 60 天（下一轮生效）"
            >
              <Switch data-testid="debug-log-switch" />
            </Form.Item>
          ) : null}
        </Form>
      </Modal>

      <Modal
        title={null}
        open={detailOpen}
        onCancel={() => { setDetailOpen(false); setDetailSquad(null); }}
        footer={null}
        width={980}
      >
        <div style={{ margin: '-20px -24px 0' }}>
          <div style={{
            padding: '22px 56px 22px 26px',
            borderBottom: '1px solid var(--aw-border)',
            background: 'var(--aw-raised)',
          }}>
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Space style={{ width: '100%', justifyContent: 'space-between' }} align="start">
                <div>
                  <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--aw-text)' }}>{detailSquad?.name}</div>
                  <div style={{ marginTop: 6, color: 'var(--aw-muted)' }}>{detailSquad?.description || '暂无描述'}</div>
                </div>
                <Space>
                  <Tag color="cyan">{detailMembers.length || detailSquad?.memberCount || 0} 位成员</Tag>
                  <Tag color="green">{roleStats.length} 类角色</Tag>
                  <Tag color="blue">{detailSdlcs.length || detailSquad?.sdlcCount || 0} 个 SDLC</Tag>
                </Space>
              </Space>
            </Space>
          </div>

          <div style={{ padding: 24 }}>
            {detailLoading ? <Spin /> : (
              <div style={{ display: 'grid', gridTemplateColumns: '1.35fr 0.65fr', gap: 18 }}>
                <div>
                  <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--aw-text)', marginBottom: 12 }}>数字员工阵容</div>
                  {detailMembers.length === 0 ? (
                    <Empty description="暂无成员，请先在成员管理中添加数字员工" />
                  ) : (
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12 }}>
                    {detailMembers.map((member, index) => (
                      <div key={member.agentId} style={{
                        border: `1px solid ${avatarPalette(index).border}`,
                        background: avatarPalette(index).background,
                        borderRadius: 8,
                        padding: 14,
                        textAlign: 'center',
                        cursor: 'default',
                        minHeight: 190,
                      }}>
                        <DigitalHeadAvatar member={member} />
                        <div style={{ fontWeight: 800, color: 'var(--aw-text)', marginTop: 10 }}>{member.agentName}</div>
                        <div style={{ color: avatarPalette(index).text, fontSize: 12, marginTop: 4 }}>
                          {member.roleName || member.roleCode || '未配置角色'}
                        </div>
                        <div style={{
                          color: 'var(--aw-muted)',
                          fontSize: 12,
                          marginTop: 10,
                          lineHeight: 1.5,
                          minHeight: 36,
                        }}>
                          {member.responsibilities || '暂无职责说明'}
                        </div>
                      </div>
                    ))}
                  </div>
                  )}
                </div>

                <div>
                  <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--aw-text)', marginBottom: 12 }}>角色构成</div>
                  <div style={{ display: 'grid', gap: 10 }}>
                    {roleStats.map((role) => (
                      <div key={role.name} style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        border: '1px solid var(--aw-border)',
                        borderRadius: 8,
                        padding: 12,
                        background: 'var(--aw-panel)',
                      }}>
                        <span>{role.name}</span>
                        <b>{role.count}</b>
                      </div>
                    ))}
                  </div>

                  <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--aw-text)', marginTop: 20, marginBottom: 6 }}>SDLC 流程</div>
                  <div style={{ display: 'grid', gap: 12 }}>
                    {sdlcGroups.map((group) => (
                      <div key={group.id} style={{
                        border: '1px solid var(--aw-border)',
                        borderRadius: 8,
                        padding: 12,
                        background: 'var(--aw-panel)',
                      }}>
                        <div style={{ fontWeight: 800, color: 'var(--aw-text)' }}>{group.name}</div>
                        <div style={{ color: 'var(--aw-muted)', fontSize: 12, marginTop: 3, marginBottom: 10 }}>
                          {group.roles.length > 0 ? group.roles.join(' / ') : '暂无角色'}
                        </div>
                        <div style={{ borderLeft: '3px solid color-mix(in srgb, var(--aw-info) 30%, var(--aw-border))', paddingLeft: 12, display: 'grid', gap: 10 }}>
                          {group.steps.length === 0 ? (
                            <span style={{ color: 'var(--aw-muted)' }}>暂无步骤</span>
                          ) : group.steps.map((step) => (
                            <div key={step.id}>
                              <b>{step.name}</b>
                              <div style={{ fontSize: 12, color: 'var(--aw-muted)' }}>{step.handlerRoleRef || step.handlerType || '-'}</div>
                            </div>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>

                  <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--aw-text)', marginTop: 20, marginBottom: 6 }}>关联执行器</div>
                  <div style={{ display: 'grid', gap: 8 }}>
                    {detailExecutors.length === 0 ? (
                      <span style={{ color: 'var(--aw-muted)' }}>暂无执行器</span>
                    ) : detailExecutors.map((executor) => (
                      <div key={executor.id} style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        gap: 8,
                        border: '1px solid var(--aw-border)',
                        borderRadius: 8,
                        padding: '8px 12px',
                        background: 'var(--aw-panel)',
                      }}>
                        <div>
                          <div style={{ fontWeight: 700, color: 'var(--aw-text)' }}>{executor.name}</div>
                          <div style={{ fontSize: 12, color: 'var(--aw-muted)' }}>{executor.agentName || '未知 Agent'}</div>
                        </div>
                        <Tag color={executor.status === 'ONLINE' ? 'green' : 'default'}>{executor.status}</Tag>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      </Modal>

      <Modal
        title="管理小队成员"
        open={membersOpen}
        onCancel={() => { setMembersOpen(false); setMembersSquadId(null); }}
        footer={null}
        width={520}
      >
        <div style={{ marginBottom: 16 }}>
          <Space.Compact style={{ width: '100%' }}>
            <Select
              style={{ width: '100%' }}
              placeholder="选择数字员工添加到小队"
              value={addAgentId}
              onChange={setAddAgentId}
              options={availableAgents.map((agent) => ({ value: agent.id, label: agent.name }))}
              showSearch
              filterOption={(input, option) => String(option?.label ?? '').toLowerCase().includes(input.toLowerCase())}
            />
            <Button type="primary" icon={<PlusOutlined />}
              disabled={!addAgentId} loading={addMemberMut.isPending}
              onClick={() => addAgentId && accessCommand(
                'READ_WRITE',
                '添加小队成员',
                () => addMemberMut.mutate(addAgentId),
              )}>
              添加
            </Button>
          </Space.Compact>
        </div>

        {membersLoading ? <Spin /> : (
          <List
            size="small"
            dataSource={members}
            locale={{ emptyText: '暂无成员' }}
            renderItem={(item: SquadMember) => (
              <List.Item
                actions={[
                  <Popconfirm key="rm" title="确认移除？" onConfirm={() => accessCommand(
                    'READ_WRITE',
                    '移除小队成员',
                    () => removeMemberMut.mutate(item.agentId),
                  )}>
                    <Button type="link" size="small" danger>移除</Button>
                  </Popconfirm>,
                ]}
              >
                <List.Item.Meta
                  title={item.agentName}
                  description={<Tag>{item.roleCode}</Tag>}
                />
              </List.Item>
            )}
          />
        )}
      </Modal>
    </>
  );
}

function SummaryPill({ label, value }: { label: string; value: number }) {
  return (
    <div className="squad-summary-pill">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function SquadMetric({ label, active = false }: { label: string; active?: boolean }) {
  return (
    <div className="squad-metric">
      <span className={`squad-metric-dot ${active ? 'is-active' : ''}`} />
      <span>{label}</span>
    </div>
  );
}

function executorText(squad: Squad) {
  const online = squad.executorOnlineCount ?? 0;
  const total = squad.executorTotalCount ?? 0;
  if (total <= 1) {
    return online > 0 ? '执行器在线' : '执行器离线';
  }
  return `${online}/${total} 在线`;
}

function sdlcText(squad: Squad) {
  const count = squad.sdlcCount ?? 0;
  return count > 0 ? `${count} 个 SDLC` : '未关联 SDLC';
}

function buildRoleStats(members: SquadMember[]) {
  const counts = new Map<string, number>();
  members.forEach((member) => {
    const key = member.roleName || member.roleCode || '未配置角色';
    counts.set(key, (counts.get(key) || 0) + 1);
  });
  return Array.from(counts.entries()).map(([name, count]) => ({ name, count }));
}

function buildSdlcGroups(members: SquadMember[]) {
  const groups = new Map<string, {
    id: string;
    name: string;
    roles: Set<string>;
    steps: NonNullable<SquadMember['sdlcSteps']>;
  }>();

  members.forEach((member) => {
    const id = String(member.sdlcId ?? member.sdlcName ?? 'none');
    const group = groups.get(id) ?? {
      id,
      name: member.sdlcName || '未关联 SDLC',
      roles: new Set<string>(),
      steps: [],
    };
    const role = member.roleName || member.roleCode;
    if (role) {
      group.roles.add(role);
    }
    if (group.steps.length === 0 && member.sdlcSteps?.length) {
      group.steps = member.sdlcSteps;
    }
    groups.set(id, group);
  });

  return Array.from(groups.values()).map((group) => ({
    id: group.id,
    name: group.name,
    roles: Array.from(group.roles),
    steps: group.steps,
  }));
}

function avatarPalette(index: number) {
  const palettes = [
    { border: 'color-mix(in srgb, var(--aw-info) 30%, var(--aw-border))', background: 'color-mix(in srgb, var(--aw-info) 10%, var(--aw-panel))', text: 'var(--aw-info)' },
    { border: 'color-mix(in srgb, var(--aw-success) 30%, var(--aw-border))', background: 'color-mix(in srgb, var(--aw-success) 10%, var(--aw-panel))', text: 'var(--aw-success)' },
    { border: '#fdba74', background: 'rgba(var(--aw-accent-rgb), .10)', text: 'var(--aw-accent-text)' },
    { border: '#fcd34d', background: 'color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))', text: 'var(--aw-warning)' },
    { border: '#c4b5fd', background: 'var(--aw-raised)', text: 'var(--aw-text)' },
  ];
  return palettes[index % palettes.length];
}

function DigitalHeadAvatar({ member }: { member: SquadMember }) {
  return <div aria-label={member.agentName} style={{ width: 68, height: 68, margin: '0 auto', borderRadius: '50%', background: 'color-mix(in srgb, var(--aw-accent) 20%, var(--aw-raised))', color: 'var(--aw-accent-text)', fontWeight: 800, fontSize: 22, display: 'grid', placeItems: 'center' }}>{(member.agentName || '?').slice(0, 2)}</div>;
}
