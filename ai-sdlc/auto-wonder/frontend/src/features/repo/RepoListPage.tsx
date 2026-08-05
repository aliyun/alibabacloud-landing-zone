import { useState } from 'react';
import { Table, Card, Button, Space, Modal, Form, Input, message } from 'antd';
import { PlusOutlined, ShareAltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createRepo, listRepos } from './api';
import type { Repo } from './api';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

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
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const runWithAccess = useAccessCommand();
  const [form] = Form.useForm();
  const [createOpen, setCreateOpen] = useState(false);

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

  const handleCreateRepo = async () => {
    await runWithAccess('READ_WRITE', '添加仓库', async () => {
      const values = await form.validateFields();
      createMutation.mutate(values);
    });
  };

  const columns: ColumnsType<Repo> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: '名称', dataIndex: 'name',
      render: (name: string, record: Repo) => <a onClick={() => navigate(`/repos/${record.id}`)}>{name}</a>,
    },
    { title: 'URL', dataIndex: 'url', ellipsis: true },
    { title: '描述', dataIndex: 'description', ellipsis: true, render: (v: string | null) => v || '-' },
    {
      title: '创建时间', dataIndex: 'gmtCreate', width: 160,
      render: (t: string) => new Date(t).toLocaleString('zh-CN'),
    },
  ];

  return (
    <Card
      title="仓库管理"
      extra={
        <Space>
          <Button icon={<ShareAltOutlined />} onClick={() => navigate('/repos/map')}>关系图</Button>
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
            rules={[{ required: true, message: '请输入仓库名称' }]}
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
            <Input placeholder="https://github.com/example/repo 或 git@example.com:org/repo.git" />
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
