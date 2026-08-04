import { useMemo, useState } from 'react';
import { Badge, Button, Popconfirm, Space, Switch, Table, Tag, Tooltip, Typography, message } from 'antd';
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

export function DingTalkBindingPanel() {
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
      map.set(agent.id, agent.roleName ? `${agent.name}（${agent.roleName}）` : agent.name);
    }
    return map;
  }, [agents]);

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
      message.success('状态已更新');
      queryClient.invalidateQueries({ queryKey: ['dingtalk-bindings'] });
    },
    onError: (error: Error) => message.error(error.message || '更新失败'),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteDingTalkBinding(id),
    onSuccess: () => {
      message.success('绑定已删除');
      queryClient.invalidateQueries({ queryKey: ['dingtalk-bindings'] });
    },
    onError: (error: Error) => message.error(error.message || '删除失败'),
  });

  function openCreate() {
    runAccessCommand('ADMIN', '新建钉钉绑定', () => {
      setDrawerMode('create');
      setEditing(null);
      setDrawerOpen(true);
    });
  }

  function openEdit(record: DingTalkBinding) {
    runAccessCommand('ADMIN', '编辑钉钉绑定', () => {
      setDrawerMode('edit');
      setEditing(record);
      setDrawerOpen(true);
    });
  }

  const columns: ColumnsType<DingTalkBinding> = [
    { title: '机器人', dataIndex: 'robotCode', width: 180, render: (v) => <Text code>{v}</Text> },
    {
      title: '关联数字人',
      dataIndex: 'agentId',
      render: (agentId: number) => agentNameById.get(agentId) || `#${agentId}`,
    },
    {
      title: '传输',
      dataIndex: 'transportMode',
      width: 110,
      render: (v: TransportMode) => <Tag>{v === 'STREAM' ? 'Stream' : 'HTTP'}</Tag>,
    },
    {
      title: '健康',
      key: 'health',
      width: 120,
      render: (_, record) => {
        if (record.transportMode === 'STREAM') {
          if (record.streamStatus === 'CONNECTED') {
            const title = record.streamStatusUpdatedAt
              ? `更新时间：${new Date(record.streamStatusUpdatedAt).toLocaleString('zh-CN')}`
              : undefined;
            return (
              <Tooltip title={title}>
                <Badge status="success" text="已连接钉钉服务" />
              </Tooltip>
            );
          }
          if (record.streamStatus === 'CONNECTING') {
            return <Badge status="processing" text="连接中" />;
          }
          if (record.streamStatus === 'FAILED') {
            const title = record.streamError || record.lastError || undefined;
            return (
              <Tooltip title={title}>
                <Badge status="error" text="连接失败" />
              </Tooltip>
            );
          }
          return <Badge status="default" text="未连接" />;
        }
        if (record.lastError) {
          return (
            <Tooltip title={record.lastError}>
              <Badge status="error" text="失败" />
            </Tooltip>
          );
        }
        if (record.lastSuccessAt) {
          return (
            <Tooltip title={`最近成功：${new Date(record.lastSuccessAt).toLocaleString('zh-CN')}`}>
              <Badge status="success" text="正常" />
            </Tooltip>
          );
        }
        return <Badge status="default" text="未回调" />;
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (_, record) => (
        <Switch
          checked={record.status === 'ENABLED'}
          loading={toggleMutation.isPending}
          checkedChildren="启用"
          unCheckedChildren="停用"
          onChange={() =>
            runAccessCommand('ADMIN', '切换钉钉绑定状态', () => toggleMutation.mutate(record))
          }
        />
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 140,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" onClick={() => openEdit(record)}>
            编辑
          </Button>
          <Popconfirm
            title="确认删除该绑定?"
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            open={deleteConfirmId === record.id}
            onOpenChange={(open) => {
              if (!open) {
                setDeleteConfirmId(null);
                return;
              }
              runAccessCommand('ADMIN', '删除钉钉绑定', () => setDeleteConfirmId(record.id));
            }}
            onConfirm={() => {
              setDeleteConfirmId(null);
              runAccessCommand('ADMIN', '删除钉钉绑定', () => deleteMutation.mutate(record.id));
            }}
          >
            <Button type="link" size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Text type="secondary">
          绑定一个钉钉机器人到一个数字人。群成员 @该机器人 即可与数字人多轮对话。
        </Text>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建绑定
        </Button>
      </div>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={bindings}
        loading={isLoading}
        pagination={false}
        locale={{ emptyText: '暂无绑定，点击「新建绑定」接入第一个钉钉机器人' }}
      />
      <DingTalkBindingDrawer
        open={drawerOpen}
        mode={drawerMode}
        record={editing}
        onClose={() => setDrawerOpen(false)}
        onSaved={() => undefined}
      />
    </Space>
  );
}
