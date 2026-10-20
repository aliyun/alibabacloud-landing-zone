import { formatDateTime } from '@/shared/i18n/format';
import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { getAgentRoleLabel } from '@/features/agent/constants';
import { useMemo, useState } from 'react';
import { Badge, Button, Card, Popconfirm, Space, Switch, Tag, Tooltip, Typography, message } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import { listAgents } from '@/features/agent/api';
import { DingTalkBindingDrawer } from './DingTalkBindingDrawer';
import {
  deleteDingTalkBinding,
  listDingTalkBindings,
  updateDingTalkBinding,
  type DingTalkBinding,
  type TransportMode,
} from './dingtalkApi';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { Text } = Typography;
const transportLabelKeys = new Map([['STREAM', 'stream'], ['HTTP_CALLBACK', 'http']] as const);

export function DingTalkBindingPanel() {
  const { t } = useTranslation(['integration', 'agent']);
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerMode, setDrawerMode] = useState<'create' | 'edit'>('create');
  const [editing, setEditing] = useState<DingTalkBinding | null>(null);
  const [deleteConfirmId, setDeleteConfirmId] = useState<number | null>(null);

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

  const toggleMutation = useMutation({
    mutationFn: (binding: DingTalkBinding) =>
      updateDingTalkBinding(binding.id, {
        appKey: binding.appKey,
        robotCode: binding.robotCode,
        agentId: binding.agentId,
        transportMode: binding.transportMode,
        streamEnv: binding.streamEnv,
        baseUrl: binding.baseUrl ?? undefined,
        regionId: binding.regionId ?? undefined,
        status: binding.status === 'ENABLED' ? 'DISABLED' : 'ENABLED',
      }),
    onSuccess: () => {
      message.success(i18n.t('integration:statusUpdated'));
      queryClient.invalidateQueries({ queryKey: ['dingtalk-bindings'] });
    },
    onError: (error: Error) => message.error(error.message || i18n.t('integration:updateFailed')),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteDingTalkBinding(id),
    onSuccess: () => {
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
      setDrawerMode('edit');
      setEditing(record);
      setDrawerOpen(true);
    });
  }

  const columns: ColumnsType<DingTalkBinding> = [
    { title: t('robot'), dataIndex: 'robotCode', width: 180, render: (v) => <Text code>{v}</Text> },
    {
      title: t('linkedAgent'),
      dataIndex: 'agentId',
      render: (agentId: number) => agentNameById.get(agentId) || `#${agentId}`,
    },
    {
      title: t('transport'),
      dataIndex: 'transportMode',
      width: 110,
      render: (v: TransportMode) => {
        const key = transportLabelKeys.get(v);
        return <Tag>{key ? t(key) : v}</Tag>;
      },
    },
    {
      title: t('health'),
      key: 'health',
      width: 120,
      render: (_, record) => {
        if (record.transportMode === 'STREAM') {
          if (record.streamStatus === 'CONNECTED') {
            const title = record.streamStatusUpdatedAt
              ? t('healthUpdated', { time: formatDateTime(record.streamStatusUpdatedAt, { dateStyle: 'medium', timeStyle: 'medium' }) })
              : undefined;
            return (
              <Tooltip title={title}>
                <Badge status="success" text={t('connected')} />
              </Tooltip>
            );
          }
          if (record.streamStatus === 'CONNECTING') {
            return <Badge status="processing" text={t('connecting')} />;
          }
          if (record.streamStatus === 'FAILED') {
            const title = record.streamError || record.lastError || undefined;
            return (
              <Tooltip title={title}>
                <Badge status="error" text={t('connectionFailed')} />
              </Tooltip>
            );
          }
          const label = record.streamStatus && record.streamStatus !== 'NOT_CONNECTED' ? record.streamStatus : t('disconnected');
          return <Badge status="default" text={label} />;
        }
        if (record.lastError) {
          return (
            <Tooltip title={record.lastError}>
              <Badge status="error" text={t('failed')} />
            </Tooltip>
          );
        }
        if (record.lastSuccessAt) {
          return (
            <Tooltip title={t('lastSuccess', { time: formatDateTime(record.lastSuccessAt, { dateStyle: 'medium', timeStyle: 'medium' }) })}>
              <Badge status="success" text={t('healthy')} />
            </Tooltip>
          );
        }
        return <Badge status="default" text={t('noCallback')} />;
      },
    },
    {
      title: t('status'),
      dataIndex: 'status',
      width: 110,
      render: (_, record) => (
        <Switch aria-label={t('toggleDing')}
          checked={record.status === 'ENABLED'}
          loading={toggleMutation.isPending}
          checkedChildren={t('enabled')}
          unCheckedChildren={t('disabled')}
          onChange={() =>
            runAccessCommand('ADMIN', t('toggleDing'), () => toggleMutation.mutate(record))
          }
        />
      ),
    },
    {
      title: t('actions'),
      key: 'actions',
      width: 140,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" onClick={() => openEdit(record)}>{t('edit')}</Button>
          <Popconfirm
            title={t('confirmDingDelete')}
            okText={t('delete')}
            cancelText={t('cancel')}
            okButtonProps={{ danger: true }}
            open={deleteConfirmId === record.id}
            onOpenChange={(open) => {
              if (!open) {
                setDeleteConfirmId(null);
                return;
              }
              runAccessCommand('ADMIN', t('deleteDing'), () => setDeleteConfirmId(record.id));
            }}
            onConfirm={() => {
              setDeleteConfirmId(null);
              runAccessCommand('ADMIN', t('deleteDing'), () => deleteMutation.mutate(record.id));
            }}
          >
            <Button type="link" size="small" danger>{t('delete')}</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card title={t('dingTitle')} extra={
      <Button type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={openCreate}>{t('newBinding')}</Button>
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
        onSaved={() => undefined}
      />
    </Card>
  );
}
