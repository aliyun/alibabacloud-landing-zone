import { useState } from 'react';
import {
  Button, Space, Modal, Form, Input, Popconfirm, message, Tag, List, Select, Spin, Popover, Empty, Pagination,
} from 'antd';
import { PlusOutlined, TeamOutlined, EditOutlined, DeleteOutlined, UserAddOutlined, EyeOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listSquads, createSquad, updateSquad, deleteSquad,
  getSquadMembers, addSquadMember, removeSquadMember,
} from './api';
import { listAgents } from '@/features/agent/api';
import type { Squad, SquadMember } from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import './SquadListPage.css';

export function SquadListPage() {
  const queryClient = useQueryClient();
  const accessCommand = useAccessCommand();
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);

  const [formOpen, setFormOpen] = useState(false);
  const [editingSquad, setEditingSquad] = useState<Squad | null>(null);
  const [form] = Form.useForm();

  const [membersOpen, setMembersOpen] = useState(false);
  const [membersSquadId, setMembersSquadId] = useState<number | null>(null);
  const [addAgentId, setAddAgentId] = useState<number | undefined>(undefined);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailSquad, setDetailSquad] = useState<Squad | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['squads', pageNum, pageSize],
    queryFn: () => listSquads({ pageNum, pageSize }),
  });

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
    mutationFn: ({ id, data: d }: { id: number; data: { name?: string; description?: string } }) => updateSquad(id, d),
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
      form.setFieldsValue({ name: squad.name, description: squad.description });
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
        updateMut.mutate({ id: editingSquad.id, data: values });
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
  const squads = data?.list ?? [];
  const memberTotal = squads.reduce((sum, squad) => sum + (squad.memberCount ?? 0), 0);
  const executorOnlineTotal = squads.reduce((sum, squad) => sum + (squad.executorOnlineCount ?? 0), 0);
  const sdlcTotal = squads.reduce((sum, squad) => sum + (squad.sdlcCount ?? 0), 0);

  return (
    <>
      <section className="squad-card-page">
        <div className="squad-card-header">
          <div>
            <h2>小队管理</h2>
            <div className="squad-card-subtitle">按小队规模、角色构成和执行器状态查看交付阵容</div>
          </div>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建小队</Button>
        </div>

        <div className="squad-summary-strip">
          <SummaryPill label="小队总数" value={data?.total ?? squads.length} />
          <SummaryPill label="数字员工总数" value={memberTotal} />
          <SummaryPill label="执行器在线" value={executorOnlineTotal} />
          <SummaryPill label="关联 SDLC" value={sdlcTotal} />
        </div>

        <Spin spinning={isLoading}>
          {squads.length === 0 && !isLoading ? (
            <Empty description="暂无小队">
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建小队</Button>
            </Empty>
          ) : (
            <div className="squad-card-grid">
              {squads.map((squad, index) => (
                <article key={squad.id} className="squad-summary-card">
                  <div className="squad-card-topline" />
                  <div className="squad-card-main">
                    <div className="squad-team-avatar">
                      <TeamOutlined />
                      <span>{index + 1}</span>
                    </div>
                    <div className="squad-card-title-area">
                      <div className="squad-card-title">{squad.name}</div>
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

        <Pagination
          className="squad-card-pagination"
          current={pageNum}
          pageSize={pageSize}
          total={data?.total ?? squads.length}
          showSizeChanger
          showTotal={(total) => `共 ${total} 条`}
          onChange={(nextPage, nextSize) => {
            setPageNum(nextPage);
            setPageSize(nextSize);
          }}
        />
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
            padding: '22px 26px',
            borderBottom: '1px solid #e5e7eb',
            background: '#f8fbff',
          }}>
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Space style={{ width: '100%', justifyContent: 'space-between' }} align="start">
                <div>
                  <div style={{ fontSize: 22, fontWeight: 800, color: '#111827' }}>{detailSquad?.name}</div>
                  <div style={{ marginTop: 6, color: '#6b7280' }}>{detailSquad?.description || '暂无描述'}</div>
                </div>
                <Space>
                  <Tag color="cyan">{detailMembers.length || detailSquad?.memberCount || 0} 位成员</Tag>
                  <Tag color="green">{roleStats.length} 类角色</Tag>
                </Space>
              </Space>
            </Space>
          </div>

          <div style={{ padding: 24 }}>
            {detailLoading ? <Spin /> : detailMembers.length === 0 ? (
              <Empty description="暂无成员，请先在成员管理中添加数字员工" />
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: '1.35fr 0.65fr', gap: 18 }}>
                <div>
                  <div style={{ fontSize: 14, fontWeight: 800, color: '#374151', marginBottom: 12 }}>数字人阵容</div>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12 }}>
                    {detailMembers.map((member, index) => (
                      <Popover
                        key={member.agentId}
                        title={member.roleName || member.roleCode || '未配置角色'}
                        content={<MemberPopover member={member} />}
                      >
                        <div style={{
                          border: `1px solid ${avatarPalette(index).border}`,
                          background: avatarPalette(index).background,
                          borderRadius: 8,
                          padding: 14,
                          textAlign: 'center',
                          cursor: 'default',
                          minHeight: 190,
                        }}>
                          <DigitalHeadAvatar member={member} index={index} />
                          <div style={{ fontWeight: 800, color: '#0f172a', marginTop: 10 }}>{member.agentName}</div>
                          <div style={{ color: avatarPalette(index).text, fontSize: 12, marginTop: 4 }}>
                            {member.roleName || member.roleCode || '未配置角色'}
                          </div>
                          <div style={{
                            color: '#64748b',
                            fontSize: 12,
                            marginTop: 10,
                            lineHeight: 1.5,
                            minHeight: 36,
                          }}>
                            {member.responsibilities || '暂无职责说明'}
                          </div>
                        </div>
                      </Popover>
                    ))}
                  </div>
                </div>

                <div>
                  <div style={{ fontSize: 14, fontWeight: 800, color: '#374151', marginBottom: 12 }}>角色构成</div>
                  <div style={{ display: 'grid', gap: 10 }}>
                    {roleStats.map((role) => (
                      <div key={role.name} style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        border: '1px solid #e5e7eb',
                        borderRadius: 8,
                        padding: 12,
                        background: '#fff',
                      }}>
                        <span>{role.name}</span>
                        <b>{role.count}</b>
                      </div>
                    ))}
                  </div>

                  <div style={{ fontSize: 14, fontWeight: 800, color: '#374151', marginTop: 20, marginBottom: 6 }}>SDLC 流程</div>
                  <div style={{ display: 'grid', gap: 12 }}>
                    {sdlcGroups.map((group) => (
                      <div key={group.id} style={{
                        border: '1px solid #e5e7eb',
                        borderRadius: 8,
                        padding: 12,
                        background: '#fff',
                      }}>
                        <div style={{ fontWeight: 800, color: '#111827' }}>{group.name}</div>
                        <div style={{ color: '#6b7280', fontSize: 12, marginTop: 3, marginBottom: 10 }}>
                          {group.roles.length > 0 ? group.roles.join(' / ') : '暂无角色'}
                        </div>
                        <div style={{ borderLeft: '3px solid #dbeafe', paddingLeft: 12, display: 'grid', gap: 10 }}>
                          {group.steps.length === 0 ? (
                            <span style={{ color: '#94a3b8' }}>暂无步骤</span>
                          ) : group.steps.map((step) => (
                            <div key={step.id}>
                              <b>{step.name}</b>
                              <div style={{ fontSize: 12, color: '#6b7280' }}>{step.handlerRoleRef || step.handlerType || '-'}</div>
                            </div>
                          ))}
                        </div>
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
    { border: '#38bdf8', background: '#f0f9ff', ring: '#0284c7', body: '#2563eb', text: '#0369a1' },
    { border: '#86efac', background: '#f0fdf4', ring: '#16a34a', body: '#22c55e', text: '#15803d' },
    { border: '#fdba74', background: '#fff7ed', ring: '#ea580c', body: '#f97316', text: '#c2410c' },
    { border: '#fcd34d', background: '#fffbeb', ring: '#d97706', body: '#f59e0b', text: '#b45309' },
    { border: '#c4b5fd', background: '#f5f3ff', ring: '#7c3aed', body: '#8b5cf6', text: '#6d28d9' },
  ];
  return palettes[index % palettes.length];
}

function roleBadge(member: SquadMember) {
  const code = member.roleCode || member.roleName || 'NA';
  const parts = code.split(/[_\s-]+/).filter(Boolean);
  if (parts.length >= 2) {
    return `${parts[0][0]}${parts[1][0]}`.toUpperCase();
  }
  return code.slice(0, 2).toUpperCase();
}

function DigitalHeadAvatar({ member, index }: { member: SquadMember; index: number }) {
  const palette = avatarPalette(index);
  const skin = ['#f8d8bd', '#f1c8a8', '#ddb18c', '#e7bc98', '#c9936d'][index % 5];
  return (
    <div style={{
      width: 68,
      height: 68,
      margin: '0 auto',
      borderRadius: '50%',
      background: '#fff',
      border: `4px solid ${palette.ring}`,
      position: 'relative',
      overflow: 'hidden',
      display: 'grid',
      placeItems: 'center',
      boxShadow: '0 8px 18px rgba(15, 23, 42, 0.12)',
    }}>
      <div style={{
        position: 'absolute',
        top: 11,
        width: 26,
        height: 26,
        borderRadius: '50%',
        background: skin,
        zIndex: 2,
      }} />
      <div style={{
        position: 'absolute',
        top: 8,
        width: 30,
        height: 15,
        borderRadius: '15px 15px 8px 8px',
        background: '#334155',
        zIndex: 3,
      }} />
      <div style={{
        position: 'absolute',
        bottom: 6,
        width: 46,
        height: 28,
        borderRadius: '24px 24px 10px 10px',
        background: palette.body,
        zIndex: 1,
      }} />
      <div style={{
        position: 'absolute',
        right: -3,
        bottom: 1,
        background: palette.ring,
        color: '#fff',
        borderRadius: 999,
        fontSize: 10,
        lineHeight: '16px',
        minWidth: 22,
        height: 16,
        padding: '0 4px',
        zIndex: 4,
      }}>
        {roleBadge(member)}
      </div>
    </div>
  );
}

function MemberPopover({ member }: { member: SquadMember }) {
  return (
    <div style={{ maxWidth: 300 }}>
      <div style={{ color: '#64748b', marginBottom: 8 }}>{member.roleCode || '-'}</div>
      <div style={{ marginBottom: 12, lineHeight: 1.6 }}>
        {member.responsibilities || '暂无职责说明'}
      </div>
      <div style={{ fontWeight: 700, marginBottom: 8 }}>{member.sdlcName || '未关联 SDLC'}</div>
      <Space size={[6, 6]} wrap>
        {(member.sdlcSteps || []).map((step) => (
          <Tag key={step.id}>{step.stepOrder} {step.name}</Tag>
        ))}
        {(member.sdlcSteps || []).length === 0 && <Tag>暂无步骤</Tag>}
      </Space>
    </div>
  );
}
