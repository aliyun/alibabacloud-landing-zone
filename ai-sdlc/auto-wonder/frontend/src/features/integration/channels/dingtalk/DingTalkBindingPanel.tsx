import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { getAgentRoleLabel } from '@/features/agent/constants';
import { useMemo, useState } from 'react';
import { Button, Card, Popconfirm, Space, Tag, Typography, message } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import { listAgents } from '@/features/agent/api';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { DingTalkBindingDrawer } from './DingTalkBindingDrawer';
import {
  deleteDingTalkBinding,
  listDingTalkBindings,
  type DingTalkBinding,
  type TransportMode,
} from './dingtalkApi';
import { GroupBindingDetailDrawer, type GroupBindingDetailModel } from '../GroupBindingDetailDrawer';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const transportLabelKeys = new Map([['STREAM', 'stream'], ['HTTP_CALLBACK', 'http']] as const);
const SHARED_MODE = 'SHARED';

export function DingTalkBindingPanel() {
  const { t } = useTranslation(['integration', 'agent']);
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerMode, setDrawerMode] = useState<'create' | 'edit'>('create');
  const [editing, setEditing] = useState<DingTalkBinding | null>(null);
  const [detail, setDetail] = useState<DingTalkBinding | null>(null);

  const { data: bindings = [], isLoading } = useQuery({
    queryKey: ['dingtalk-bindings'],
    queryFn: listDingTalkBindings,
  });

  const { data: agents = [] } = useQuery({
    queryKey: ['agents-for-binding'],
    queryFn: () => listAgents({ page: 1, size: 200 }),
  });

  const agentNameById = useMemo(() => {
    const map = new Map<number, string>();
    for (const agent of agents) {
      map.set(agent.id, getAgentRoleLabel(agent.roleCode, agent.roleName) ? t('display.bindingLabel', { ns: 'agent', name: agent.name, label: getAgentRoleLabel(agent.roleCode, agent.roleName) }) : agent.name);
    }
    return map;
  }, [agents, t]);

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteDingTalkBinding(id),
    onSuccess: () => {
      setDetail(null);
      message.success(i18n.t('integration:bindingDeleted'));
      queryClient.invalidateQueries({ queryKey: ['dingtalk-bindings'] });
    },
    onError: (error: Error) => message.error(error.message || i18n.t('integration:deleteFailed')),
  });

  function openCreate() {
    runAccessCommand('ADMIN', t('newDingCommand'), () => {
      setDrawerMode('create');
      setEditing(null);
      setDrawerOpen(true);
    });
  }

  function openEdit(record: DingTalkBinding) {
    runAccessCommand('ADMIN', t('editDingCommand'), () => {
      setDetail(null);
      setDrawerMode('edit');
      setEditing(record);
      setDrawerOpen(true);
    });
  }

  /** Saving the binding is enough: close the form and show the refreshed list. */
  function handleSaved() {
    setDrawerOpen(false);
    setEditing(null);
    setDetail(null);
  }

  function diagnosticLabel(record: DingTalkBinding): string {
    if (record.bindingMode === SHARED_MODE) {
      return record.lastError ? t('failed') : t('sharedHealthManaged');
    }
    if (record.transportMode === 'STREAM') {
      if (record.streamStatus === 'CONNECTED') return t('connected');
      if (record.streamStatus === 'CONNECTING') return t('connecting');
      if (record.streamStatus === 'FAILED') return record.streamError || record.lastError || t('connectionFailed');
      return record.streamStatus && record.streamStatus !== 'NOT_CONNECTED'
        ? record.streamStatus
        : t('disconnected');
    }
    if (record.lastError) return t('failed');
    if (record.lastSuccessAt) return t('healthy');
    return t('noCallback');
  }

  function toDetailModel(record: DingTalkBinding): GroupBindingDetailModel {
    const transportKey = transportLabelKeys.get(record.transportMode as TransportMode);
    return {
      bindingMode: record.bindingMode,
      groupId: record.bindingMode === SHARED_MODE ? record.groupId : null,
      agentLabel: agentNameById.get(record.agentId) || `#${record.agentId}`,
      robotIdentity: record.appKey,
      transportLabel: transportKey ? t(transportKey) : record.transportMode,
      enabled: record.status === 'ENABLED',
      diagnostics: [{ label: t('health'), value: diagnosticLabel(record) }],
    };
  }

  const columns: ColumnsType<DingTalkBinding> = [
    {
      title: t('groupIdOrMode'),
      key: 'group',
      width: 260,
      render: (_, record) => (
        <Space direction="vertical" size={2} style={{ minWidth: 0, maxWidth: '100%' }}>
          <EllipsisText tooltip={record.bindingMode === SHARED_MODE ? record.groupId ?? t('notAvailable') : record.appKey} style={{ maxWidth: 250 }}>
            <Typography.Text code>{record.bindingMode === SHARED_MODE ? (record.groupId || t('notAvailable')) : record.appKey}</Typography.Text>
          </EllipsisText>
          <Tag color={record.bindingMode === SHARED_MODE ? 'blue' : 'default'} style={{ marginInlineEnd: 0 }}>
            {record.bindingMode === SHARED_MODE ? t('sharedMode') : t('legacyMode')}
          </Tag>
        </Space>
      ),
    },
    {
      title: t('linkedAgent'),
      dataIndex: 'agentId',
      width: 220,
      render: (agentId: number) => {
        const label = agentNameById.get(agentId) || `#${agentId}`;
        return <EllipsisText tooltip={label}>{label}</EllipsisText>;
      },
    },
    {
      title: t('status'),
      dataIndex: 'status',
      width: 100,
      render: (status: DingTalkBinding['status']) => {
        const label = status === 'ENABLED' ? t('statusEnabled')
          : status === 'DISABLED' ? t('statusDisabled') : status;
        const color = status === 'ENABLED' ? 'green' : status === 'DISABLED' ? 'default' : 'red';
        return <Tag color={color}>{label}</Tag>;
      },
    },
    {
      title: t('actions'),
      key: 'actions',
      width: 180,
      fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => setDetail(record)}>{t('detail')}</Button>
          <Button type="link" size="small" onClick={() => openEdit(record)}>{t('edit')}</Button>
          <Popconfirm
            title={t('confirmDeleteBinding')}
            description={t('deleteHelp')}
            okText={t('deleteBinding')}
            cancelText={t('cancel')}
            okButtonProps={{ danger: true }}
            onConfirm={() => runAccessCommand('ADMIN', t('deleteBinding'), () => deleteMutation.mutate(record.id))}
          >
            <Button type="link" size="small" danger>{t('delete')}</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card title={t('dingTitle')} extra={
      <Button type="primary" icon={<PlusOutlined aria-hidden="true" />} aria-label={t('newBinding')} onClick={openCreate}>{t('newBinding')}</Button>
    }>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>{t('dingDescription')}</Typography.Paragraph>
      <Table
        scroll={{ x: 900 }}
        rowKey="id"
        columns={columns}
        dataSource={bindings}
        loading={isLoading}
        pagination={false}
        locale={{ emptyText: t('dingEmpty') }}
      />
      <DingTalkBindingDrawer
        open={drawerOpen}
        mode={drawerMode}
        record={editing}
        onClose={() => setDrawerOpen(false)}
        onSaved={handleSaved}
        onExistingBinding={(binding) => {
          setDrawerOpen(false);
          setEditing(null);
          setDetail(binding);
        }}
      />
      <GroupBindingDetailDrawer
        open={detail !== null}
        model={detail ? toDetailModel(detail) : null}
        onClose={() => setDetail(null)}
        onEdit={() => { if (detail) openEdit(detail); }}
        onDelete={() => { if (detail) deleteMutation.mutate(detail.id); }}
        deleteLoading={deleteMutation.isPending}
      />
    </Card>
  );
}
