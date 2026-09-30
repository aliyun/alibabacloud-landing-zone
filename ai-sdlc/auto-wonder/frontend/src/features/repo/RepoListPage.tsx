import { useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, message, Popconfirm } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlusOutlined, ShareAltOutlined, DeleteOutlined } from '@ant-design/icons';
import { Link, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createRepo, listRepos, deleteRepo } from './api';
import type { Repo } from './api';
import type { ColumnsType } from 'antd/es/table';
import { isValidRepoName, REPO_NAME_INVALID_MESSAGE } from './repoNameValidation';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { EllipsisText } from '@/shared/ui/EllipsisText';

const SCP_LIKE_SSH_REPO_PATTERN = /^[\w.-]+@[\w.-]+:[\w./~@-]+(?:\.git)?$/;
const URL_REPO_PATTERN = /^(https?:\/\/|ssh:\/\/|git:\/\/).+/i;

function isValidRepoUrl(value?: string) {
  const trimmed = value?.trim();
  if (!trimmed) {
    return true;
  }
  return URL_REPO_PATTERN.test(trimmed) || SCP_LIKE_SSH_REPO_PATTERN.test(trimmed);
}

export function RepoListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const queryClient = useQueryClient();
  const runWithAccess = useAccessCommand();
  const [form] = Form.useForm();
  const [createOpen, setCreateOpen] = useState(false);
  const [pendingDeleteId, setPendingDeleteId] = useState<number | null>(null);

  // The page lives in the /repos hub now, so the relation map is a sibling tab on the
  // same route rather than a separate navigation.
  const switchToMapTab = () => {
    const next = new URLSearchParams(searchParams);
    next.set('tab', 'map');
    setSearchParams(next, { replace: true });
  };

  const { data: repos = [], isLoading } = useQuery({
    queryKey: ['repos', 1, 100],
    queryFn: () => listRepos({ page: 1, size: 100 }),
  });

  const createMutation = useMutation({
    mutationFn: createRepo,
    onSuccess: () => {
      message.success('仓库已添加');
      setCreateOpen(false);
      form.resetFields();
      queryClient.invalidateQueries({ queryKey: ['repos'] });
    },
    onError: (error: Error) => {
      message.error(error.message || '添加仓库失败');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteRepo(id),
    onSuccess: () => {
      message.success('仓库已删除');
      setPendingDeleteId(null);
      queryClient.invalidateQueries({ queryKey: ['repos'] });
      queryClient.invalidateQueries({ queryKey: ['repo-relations'] });
    },
    onError: (error: Error) => {
      setPendingDeleteId(null);
      message.error(error.message || '删除仓库失败');
    },
  });

  const handleCreateRepo = async () => {
    await runWithAccess('READ_WRITE', '添加仓库', async () => {
      // 校验失败时 antd 已在表单项下方展示错误信息，这里吞掉拒绝避免未处理 Promise。
      const values = await form.validateFields().catch(() => null);
      if (values) {
        createMutation.mutate(values);
      }
    });
  };

  const columns: ColumnsType<Repo> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: '名称', dataIndex: 'name', align: 'left', width: 220, ellipsis: { showTitle: false },
      render: (name: string, record: Repo) => (
        <EllipsisText tooltip={name}><Link to={`/repos/${record.id}`}>{name}</Link></EllipsisText>
      ),
    },
    {
      title: 'URL', dataIndex: 'url', align: 'left', width: 260, ellipsis: { showTitle: false },
      render: (v: string) => <EllipsisText>{v}</EllipsisText>,
    },
    {
      title: '描述', dataIndex: 'description', align: 'left', width: 240, ellipsis: { showTitle: false },
      render: (v: string | null) => (v ? <EllipsisText>{v}</EllipsisText> : '-'),
    },
    {
      title: '创建时间', dataIndex: 'gmtCreate', width: 160,
      render: (t: string) => new Date(t).toLocaleString('zh-CN'),
    },
    {
      title: '操作', width: 80,
      render: (_: unknown, record: Repo) => (
        <Popconfirm
          title={`确认删除仓库「${record.name}」？`}
          description="删除后仓库及其所有关系将一并移除，此操作不可恢复。"
          open={pendingDeleteId === record.id}
          onOpenChange={(open) => {
            if (!open) setPendingDeleteId(null);
          }}
          onConfirm={() => runWithAccess(
            'READ_WRITE',
            '删除仓库',
            () => deleteMutation.mutate(record.id),
          )}
          okText="删除"
          okButtonProps={{ danger: true }}
          cancelText="取消"
        >
          <Button
            type="link"
            size="small"
            danger
            aria-label={`删除仓库 ${record.name}`}
            title={`删除仓库 ${record.name}`}
            icon={<DeleteOutlined />}
            onClick={() => runWithAccess(
              'READ_WRITE',
              '删除仓库',
              () => setPendingDeleteId(record.id),
            )}
          />
        </Popconfirm>
      ),
    },
  ];

  return (
    <Card
      className="aw-content-card"
      extra={
        <Space>
          <Button icon={<ShareAltOutlined />} onClick={switchToMapTab}>关系图</Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => runWithAccess('READ_WRITE', '添加仓库', () => setCreateOpen(true))}
          >
            添加仓库
          </Button>
        </Space>
      }
    >
      <Table
        scroll={{ x: 1030 }}
        rowKey="id"
        columns={columns}
        dataSource={repos}
        loading={isLoading}
        pagination={false}
      />
      <Modal
        title="添加仓库"
        open={createOpen}
        okText="确定"
        cancelText="取消"
        confirmLoading={createMutation.isPending}
        onOk={handleCreateRepo}
        onCancel={() => {
          setCreateOpen(false);
          form.resetFields();
        }}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
        >
          <Form.Item
            label="仓库名称"
            name="name"
            rules={[
              { required: true, message: '请输入仓库名称' },
              {
                validator: (_, value) => (
                  isValidRepoName(value)
                    ? Promise.resolve()
                    : Promise.reject(new Error(REPO_NAME_INVALID_MESSAGE))
                ),
              },
            ]}
          >
            <Input placeholder="auto-wonder" />
          </Form.Item>
          <Form.Item
            label="仓库地址"
            name="url"
            rules={[
              { required: true, message: '请输入仓库地址' },
              {
                validator: (_, value) => (
                  isValidRepoUrl(value)
                    ? Promise.resolve()
                    : Promise.reject(new Error('请输入合法 URL'))
                ),
              },
            ]}
          >
            <Input placeholder="https://github.com/example/repo 或 git@example.com:workspace/repo.git" />
          </Form.Item>
          <Form.Item label="默认分支" name="defaultBranch">
            <Input placeholder="main" />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
