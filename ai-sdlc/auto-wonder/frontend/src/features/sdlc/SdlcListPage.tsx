import { useState } from 'react';
import { Table, Card, Tag, Button, Space, Popconfirm, message, Modal, Form, Input, Select, Tabs } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { listSdlcTemplates, createSdlcTemplate, deleteSdlcTemplate, enableSdlcTemplate, disableSdlcTemplate } from './api';
import type { SdlcTemplate } from './api';
import type { ColumnsType } from 'antd/es/table';
import { SquadTemplateGallery } from './SquadTemplateGallery';
import { SDLC_AI_ENABLED } from './featureFlags';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const statusMap: Record<string, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: '草稿' },
  ENABLED: { color: 'success', label: '已启用' },
  DISABLED: { color: 'warning', label: '已禁用' },
};

export function SdlcListPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const accessCommand = useAccessCommand();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm();

  const { data = [], isLoading } = useQuery({
    queryKey: ['sdlcs', page, size],
    queryFn: () => listSdlcTemplates({ page, size }),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['sdlcs'] });

  const createMut = useMutation({
    mutationFn: createSdlcTemplate,
    onSuccess: (newSdlc) => {
      invalidate();
      setCreateOpen(false);
      form.resetFields();
      message.success('创建成功');
      navigate(`/sdlcs/${newSdlc.id}`);
    },
  });

  const deleteMut = useMutation({
    mutationFn: deleteSdlcTemplate,
    onSuccess: () => { invalidate(); message.success('已删除'); },
    onError: (error: Error) => { message.error(error.message || '删除失败'); },
  });

  const enableMut = useMutation({
    mutationFn: enableSdlcTemplate,
    onSuccess: () => { invalidate(); message.success('已启用'); },
  });

  const disableMut = useMutation({
    mutationFn: disableSdlcTemplate,
    onSuccess: () => { invalidate(); message.success('已禁用'); },
  });

  const columns: ColumnsType<SdlcTemplate> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: '名称', dataIndex: 'name',
      render: (name: string, record) => <a onClick={() => navigate(`/sdlcs/${record.id}`)}>{name}</a>,
    },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    { title: '工单类型', dataIndex: 'workType', width: 90, render: (v: string | null) => v || '通用' },
    { title: '步骤数', width: 80, render: (_, record) => record.steps?.length ?? 0 },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (s: string) => <Tag color={statusMap[s]?.color}>{statusMap[s]?.label || s}</Tag>,
    },
    {
      title: '操作', width: 180,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" onClick={() => navigate(`/sdlcs/${record.id}`)}>详情</Button>
          {record.status === 'DRAFT' || record.status === 'DISABLED' ? (
            <Popconfirm title="确定启用？" onConfirm={() => accessCommand(
              'READ_WRITE',
              '启用 SDLC 模版',
              () => enableMut.mutate(record.id),
            )}>
              <Button type="link" size="small">启用</Button>
            </Popconfirm>
          ) : (
            <Popconfirm title="确定禁用？" onConfirm={() => accessCommand(
              'READ_WRITE',
              '禁用 SDLC 模版',
              () => disableMut.mutate(record.id),
            )}>
              <Button type="link" size="small" danger>禁用</Button>
            </Popconfirm>
          )}
          {record.status !== 'ENABLED' && (
            <Popconfirm title="确定删除该模版？" onConfirm={() => accessCommand(
              'READ_WRITE',
              '删除 SDLC 模版',
              () => deleteMut.mutate(record.id),
            )}>
              <Button type="link" size="small" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Tabs
      defaultActiveKey="templates"
      items={[
        {
          key: 'templates',
          label: '流程模版',
          children: (
            <>
              <Card
                title="SDLC 流程模版"
                extra={
                  <Space>
                    {SDLC_AI_ENABLED && (
                      <Button onClick={() => accessCommand(
                        'READ_WRITE',
                        'AI 生成 SDLC',
                        () => navigate('/sdlcs/generate'),
                      )}>AI 生成</Button>
                    )}
                    <Button type="primary" icon={<PlusOutlined />} onClick={() => accessCommand(
                      'READ_WRITE',
                      '新建 SDLC 模版',
                      () => setCreateOpen(true),
                    )}>新建</Button>
                  </Space>
                }
              >
                <Table
                  rowKey="id"
                  columns={columns}
                  dataSource={data}
                  loading={isLoading}
                  pagination={{
                    current: page, pageSize: size,
                    onChange: (p, ps) => { setPage(p); setSize(ps); },
                    showTotal: (t) => `共 ${t} 条`,
                  }}
                />
              </Card>

              <Modal
                title="新建 SDLC 模版"
                open={createOpen}
                onOk={() => form.validateFields().then(v => accessCommand(
                  'READ_WRITE',
                  '新建 SDLC 模版',
                  () => createMut.mutate(v),
                ))}
                onCancel={() => setCreateOpen(false)}
                confirmLoading={createMut.isPending}
              >
                <Form form={form} layout="vertical">
                  <Form.Item name="name" label="模版名称" rules={[{ required: true, message: '请输入名称' }]}>
                    <Input placeholder="如: 标准需求开发流程" />
                  </Form.Item>
                  <Form.Item name="description" label="描述">
                    <Input.TextArea rows={2} placeholder="描述该流程模版的用途" />
                  </Form.Item>
                  <Form.Item name="workType" label="工单类型">
                    <Select allowClear placeholder="可选绑定工单类型"
                      options={[
                        { value: 'REQUIREMENT', label: '需求' },
                        { value: 'TASK', label: '任务' },
                        { value: 'BUG', label: 'Bug' },
                      ]} />
                  </Form.Item>
                </Form>
              </Modal>
            </>
          ),
        },
        {
          key: 'gallery',
          label: '模版间',
          children: <SquadTemplateGallery />,
        },
      ]}
    />
  );
}
