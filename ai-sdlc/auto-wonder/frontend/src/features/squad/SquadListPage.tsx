import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { formatNumber } from '@/shared/i18n/format';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { getAgentRoleLabel } from '@/features/agent/constants';
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Button, Space, Modal, Form, Input, Popconfirm, message, Tag, List, Select, Spin, Empty, Switch,
} from 'antd';
import { CloseOutlined, PlusOutlined, EditOutlined, DeleteOutlined, UserAddOutlined, EyeOutlined, CopyOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listAllSquads, createSquad, updateSquad, deleteSquad, getSquad,
  getSquadMembers, addSquadMember, removeSquadMember,
} from './api';
import { listAgents } from '@/features/agent/api';
import type { Squad, SquadMember } from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { SquadCopyModal } from './SquadCopyModal';
import './SquadListPage.css';

export function SquadListPage() {
  const { t } = useTranslation('squad');
  const queryClient = useQueryClient();
  const accessCommand = useAccessCommand();

  const [formOpen, setFormOpen] = useState(false);
  const [editingSquad, setEditingSquad] = useState<Squad | null>(null);
  const [form] = Form.useForm();
  useLocalizedValidation(form, formOpen);

  const [membersOpen, setMembersOpen] = useState(false);
  const [membersSquadId, setMembersSquadId] = useState<number | null>(null);
  const [addAgentId, setAddAgentId] = useState<number | undefined>(undefined);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailSquad, setDetailSquad] = useState<Squad | null>(null);
  const [copySquad, setCopySquad] = useState<Squad | null>(null);
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
      message.warning(i18n.t('deepLinkUnavailable', { ns: 'squad', id: deepLinkSquadId }));
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
    onSuccess: () => { invalidateSquads(); setFormOpen(false); form.resetFields(); message.success(i18n.t('created', { ns: 'squad' })); },
  });

  const updateMut = useMutation({
    mutationFn: ({ id, data: d }: {
      id: number;
      data: { name?: string; description?: string; ownerId?: number | null; debugLogEnabled?: boolean };
    }) => updateSquad(id, d),
    onSuccess: () => { invalidateSquads(); setFormOpen(false); setEditingSquad(null); form.resetFields(); message.success(i18n.t('saved', { ns: 'squad' })); },
  });

  const deleteMut = useMutation({
    mutationFn: deleteSquad,
    onSuccess: () => { invalidateSquads(); message.success(i18n.t('deleted', { ns: 'squad' })); },
  });

  const addMemberMut = useMutation({
    mutationFn: (agentId: number) => addSquadMember(membersSquadId!, agentId),
    onSuccess: () => { invalidateMembers(); invalidateSquads(); setAddAgentId(undefined); message.success(i18n.t('added', { ns: 'squad' })); },
  });

  const removeMemberMut = useMutation({
    mutationFn: (agentId: number) => removeSquadMember(membersSquadId!, agentId),
    onSuccess: () => { invalidateMembers(); invalidateSquads(); message.success(i18n.t('removed', { ns: 'squad' })); },
  });

  const openCreate = () => {
    accessCommand('READ_WRITE', t('create'), () => {
      setEditingSquad(null);
      form.resetFields();
      setFormOpen(true);
    });
  };

  const openEdit = (squad: Squad) => {
    accessCommand('READ_WRITE', t('editSquad'), () => {
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
    accessCommand('READ_WRITE', t('manageMembers'), () => {
      setMembersSquadId(squad.id);
      setMembersOpen(true);
    });
  };

  // 复制沿用源空间的读写权限控制；前置检查在弹窗内执行，缺在线版本时弹窗只列名称、不进选择流程。
  const openCopy = (squad: Squad) => {
    accessCommand('READ_WRITE', t('copyToWorkspace'), () => setCopySquad(squad));
  };

  const openDetail = (squad: Squad) => {
    setDetailSquad(squad);
    setDetailOpen(true);
  };

  const handleFormSubmit = (values: { name: string; description?: string; debugLogEnabled?: boolean }) => {
    accessCommand('READ_WRITE', editingSquad ? t('editSquad') : t('create'), () => {
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
            <SummaryPill label={t('squadTotal')} value={squads.length} />
            <SummaryPill label={t('agentTotal')} value={memberTotal} />
            <SummaryPill label={t('executorsOnline')} value={executorOnlineTotal} />
            <SummaryPill label={t('linkedSdlcs')} value={sdlcTotal} />
          </div>
          <Button type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={openCreate}>{t('create')}</Button>
        </div>

        <Spin spinning={isLoading} aria-label={t('loading')}>
          {squads.length === 0 && !isLoading ? (
            <Empty description={t('emptySquads')} />
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
                          <Tag color="orange" style={{ marginLeft: 6 }}>{t('debug')}</Tag>
                        ) : null}
                      </div>
                      <div className="squad-card-description">{squad.description || t('noDescription')}</div>
                    </div>
                  </div>

                  <div className="squad-metric-grid">
                    <SquadMetric label={t('agentCount', { count: squad.memberCount ?? 0 })} />
                    <SquadMetric label={t('roleCount', { count: squad.roleCount ?? 0 })} />
                    <SquadMetric label={executorText(squad)} active={(squad.executorOnlineCount ?? 0) > 0} />
                    <SquadMetric label={sdlcText(squad)} />
                  </div>

                  <div className="squad-card-footer">
                    <Button type="link" size="small" icon={<EyeOutlined aria-hidden="true" />} onClick={() => openDetail(squad)}>{t('details')}</Button>
                    <Button type="link" size="small" icon={<UserAddOutlined aria-hidden="true" />} onClick={() => openMembers(squad)}>{t('members')}</Button>
                    <Button type="link" size="small" icon={<EditOutlined aria-hidden="true" />} onClick={() => openEdit(squad)}>{t('edit')}</Button>
                    <Button type="link" size="small" icon={<CopyOutlined aria-hidden="true" />} onClick={() => openCopy(squad)}>{t('copyToWorkspace')}</Button>
                    <Popconfirm okText={t('confirm')} cancelText={t('cancel')} title={t('confirmDelete')} onConfirm={() => accessCommand(
                      'READ_WRITE',
                      t('deleteSquad'),
                      () => deleteMut.mutate(squad.id),
                    )}>
                      <Button type="link" size="small" danger icon={<DeleteOutlined aria-hidden="true" />}>{t('delete')}</Button>
                    </Popconfirm>
                  </div>
                </article>
              ))}
            </div>
          )}
        </Spin>
      </section>

      <Modal
        closable={{ 'aria-label': t('close'), closeIcon: <CloseOutlined aria-hidden="true" /> }}
        title={editingSquad ? t('editSquad') : t('create')}
        open={formOpen}
        okText={t('confirm')}
        cancelText={t('cancel')}
        onOk={() => form.submit()}
        onCancel={() => { setFormOpen(false); setEditingSquad(null); }}
        confirmLoading={createMut.isPending || updateMut.isPending}
      >
        <Form form={form} layout="vertical" onFinish={handleFormSubmit}>
          <Form.Item name="name" label={t('name')} rules={[{ required: true, message: t('nameRequired') }]}>
            <Input placeholder={t('namePlaceholder')} />
          </Form.Item>
          <Form.Item name="description" label={t('description')}>
            <Input.TextArea rows={3} placeholder={t('descriptionPlaceholder')} />
          </Form.Item>
          {editingSquad ? (
            <Form.Item
              name="debugLogEnabled"
              label={t('debugLogs')}
              valuePropName="checked"
              extra={t('debugLogsHelp')}
            >
              <Switch aria-label={t('debugLogs')} data-testid="debug-log-switch" />
            </Form.Item>
          ) : null}
        </Form>
      </Modal>

      <Modal
        closable={{ 'aria-label': t('close'), closeIcon: <CloseOutlined aria-hidden="true" /> }}
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
                  <div style={{ marginTop: 6, color: 'var(--aw-muted)' }}>{detailSquad?.description || t('noDescription')}</div>
                </div>
                <Space>
                  <Tag color="cyan">{t('memberCount', { count: detailMembers.length || detailSquad?.memberCount || 0 })}</Tag>
                  <Tag color="green">{t('roleCount', { count: roleStats.length })}</Tag>
                  <Tag color="blue">{t('sdlcCount', { count: detailSdlcs.length || detailSquad?.sdlcCount || 0 })}</Tag>
                </Space>
              </Space>
            </Space>
          </div>

          <div style={{ padding: 24 }}>
            {detailLoading ? <Spin aria-label={t('loading')} /> : (
              <div style={{ display: 'grid', gridTemplateColumns: '1.35fr 0.65fr', gap: 18 }}>
                <div>
                  <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--aw-text)', marginBottom: 12 }}>{t('agentLineup')}</div>
                  {detailMembers.length === 0 ? (
                    <Empty description={t('emptyLineup')} />
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
                          {getAgentRoleLabel(member.roleCode, member.roleName) || t('roleUnset')}
                        </div>
                        <div style={{
                          color: 'var(--aw-muted)',
                          fontSize: 12,
                          marginTop: 10,
                          lineHeight: 1.5,
                          minHeight: 36,
                        }}>
                          {member.responsibilities || t('noResponsibilities')}
                        </div>
                      </div>
                    ))}
                  </div>
                  )}
                </div>

                <div>
                  <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--aw-text)', marginBottom: 12 }}>{t('roleComposition')}</div>
                  <div style={{ display: 'grid', gap: 10 }}>
                    {roleStats.map((role) => (
                      <div key={role.key} style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        border: '1px solid var(--aw-border)',
                        borderRadius: 8,
                        padding: 12,
                        background: 'var(--aw-panel)',
                      }}>
                        <span>{role.name}</span>
                        <b>{formatNumber(role.count)}</b>
                      </div>
                    ))}
                  </div>

                  <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--aw-text)', marginTop: 20, marginBottom: 6 }}>{t('sdlcWorkflows')}</div>
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
                          {group.roles.length > 0 ? group.roles.join(' / ') : t('noRoles')}
                        </div>
                        <div style={{ borderLeft: '3px solid color-mix(in srgb, var(--aw-info) 30%, var(--aw-border))', paddingLeft: 12, display: 'grid', gap: 10 }}>
                          {group.steps.length === 0 ? (
                            <span style={{ color: 'var(--aw-muted)' }}>{t('noSteps')}</span>
                          ) : group.steps.map((step) => (
                            <div key={step.id}>
                              <b>{step.name}</b>
                              <div style={{ fontSize: 12, color: 'var(--aw-muted)' }}>{step.handlerRoleRef || handlerTypeLabel(step.handlerType) || '-'}</div>
                            </div>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>

                  <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--aw-text)', marginTop: 20, marginBottom: 6 }}>{t('relatedExecutors')}</div>
                  <div style={{ display: 'grid', gap: 8 }}>
                    {detailExecutors.length === 0 ? (
                      <span style={{ color: 'var(--aw-muted)' }}>{t('noExecutors')}</span>
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
                          <div style={{ fontSize: 12, color: 'var(--aw-muted)' }}>{executor.agentName || t('unknownAgent')}</div>
                        </div>
                        <Tag color={executor.status === 'ONLINE' ? 'green' : 'default'}>{executorStatusLabel(executor.status)}</Tag>
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
        closable={{ 'aria-label': t('close'), closeIcon: <CloseOutlined aria-hidden="true" /> }}
        title={t('manageMembers')}
        open={membersOpen}
        onCancel={() => { setMembersOpen(false); setMembersSquadId(null); }}
        footer={null}
        width={520}
      >
        <div style={{ marginBottom: 16 }}>
          <Space.Compact style={{ width: '100%' }}>
            <Select
              style={{ width: '100%' }}
              placeholder={t('selectAgent')}
              aria-label={t('selectAgent')}
              value={addAgentId}
              onChange={setAddAgentId}
              options={availableAgents.map((agent) => ({ value: agent.id, label: agent.name }))}
              showSearch
              filterOption={(input, option) => String(option?.label ?? '').toLowerCase().includes(input.toLowerCase())}
            />
            <Button type="primary" icon={<PlusOutlined aria-hidden="true" />}
              disabled={!addAgentId} loading={addMemberMut.isPending}
              onClick={() => addAgentId && accessCommand(
                'READ_WRITE',
                t('addMember'),
                () => addMemberMut.mutate(addAgentId),
              )}>
              {t('add')}
            </Button>
          </Space.Compact>
        </div>

        {membersLoading ? <Spin aria-label={t('loading')} /> : (
          <List
            size="small"
            dataSource={members}
            locale={{ emptyText: t('emptyMembers') }}
            renderItem={(item: SquadMember) => (
              <List.Item
                actions={[
                  <Popconfirm okText={t('confirm')} cancelText={t('cancel')} key="rm" title={t('confirmRemove')} onConfirm={() => accessCommand(
                    'READ_WRITE',
                    t('removeMember'),
                    () => removeMemberMut.mutate(item.agentId),
                  )}>
                    <Button type="link" size="small" danger>{t('remove')}</Button>
                  </Popconfirm>,
                ]}
              >
                <List.Item.Meta
                  title={item.agentName}
                  description={<Tag>{getAgentRoleLabel(item.roleCode, item.roleName) || t('roleUnset')}</Tag>}
                />
              </List.Item>
            )}
          />
        )}
      </Modal>

      <SquadCopyModal squad={copySquad} onClose={() => setCopySquad(null)} />
    </>
  );
}

function SummaryPill({ label, value }: { label: string; value: number }) {
  useTranslation('squad');
  return (
    <div className="squad-summary-pill">
      <span>{label}</span>
      <strong>{formatNumber(value)}</strong>
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
    return i18n.t(online > 0 ? 'executorOnline' : 'executorOffline', { ns: 'squad' });
  }
  return i18n.t('onlineRatio', { ns: 'squad', online, total });
}

function sdlcText(squad: Squad) {
  const count = squad.sdlcCount ?? 0;
  return count > 0 ? i18n.t('sdlcCount', { ns: 'squad', count }) : i18n.t('noSdlc', { ns: 'squad' });
}

function buildRoleStats(members: SquadMember[]) {
  const counts = new Map<string, { key: string; name: string; count: number }>();
  members.forEach((member) => {
    const key = member.roleName || member.roleCode || '';
    const role = counts.get(key) ?? {
      key, name: getAgentRoleLabel(member.roleCode, member.roleName) || i18n.t('roleUnset', { ns: 'squad' }), count: 0,
    };
    role.count += 1;
    counts.set(key, role);
  });
  return Array.from(counts.values());
}

function buildSdlcGroups(members: SquadMember[]) {
  const groups = new Map<string, {
    id: string;
    name: string;
    roles: Map<string, string>;
    steps: NonNullable<SquadMember['sdlcSteps']>;
  }>();

  members.forEach((member) => {
    const id = String(member.sdlcId ?? member.sdlcName ?? 'none');
    const group = groups.get(id) ?? {
      id,
      name: member.sdlcName || i18n.t('noSdlc', { ns: 'squad' }),
      roles: new Map<string, string>(),
      steps: [],
    };
    const role = member.roleName || member.roleCode;
    if (role) {
      group.roles.set(role, getAgentRoleLabel(member.roleCode, member.roleName));
    }
    if (group.steps.length === 0 && member.sdlcSteps?.length) {
      group.steps = member.sdlcSteps;
    }
    groups.set(id, group);
  });

  return Array.from(groups.values()).map((group) => ({
    id: group.id,
    name: group.name,
    roles: Array.from(group.roles.values()),
    steps: group.steps,
  }));
}

const executorStatusKeys = new Map([
  ['ONLINE', 'executorStatus.ONLINE'], ['OFFLINE', 'executorStatus.OFFLINE'],
  ['BUSY', 'executorStatus.BUSY'], ['REVOKED', 'executorStatus.REVOKED'],
] as const);
function executorStatusLabel(status: string) {
  const key = executorStatusKeys.get(status as Parameters<typeof executorStatusKeys.get>[0]);
  return key ? i18n.t(key, { ns: 'squad' }) : status;
}
const handlerTypeKeys = new Map([
  ['AGENT', 'handlerType.AGENT'], ['HUMAN', 'handlerType.HUMAN'], ['SYSTEM', 'handlerType.SYSTEM'],
] as const);
function handlerTypeLabel(handlerType: string) {
  const key = handlerTypeKeys.get(handlerType as Parameters<typeof handlerTypeKeys.get>[0]);
  return key ? i18n.t(key, { ns: 'squad' }) : handlerType;
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
