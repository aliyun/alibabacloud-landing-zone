import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { useState } from 'react';
import { Card, Button, Space, Modal, Form, Input, message, Popconfirm } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlusOutlined, ShareAltOutlined, DeleteOutlined } from '@ant-design/icons';
import { Link, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createRepo, listRepos, deleteRepo } from './api';
import type { Repo } from './api';
import type { ColumnsType } from 'antd/es/table';
import { isValidRepoName, repoNameInvalidMessage } from './repoNameValidation';
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
  const { t } = useTranslation('repo');
  const [searchParams, setSearchParams] = useSearchParams();
  const queryClient = useQueryClient();
  const runWithAccess = useAccessCommand();
  const [form] = Form.useForm();
  const [createOpen, setCreateOpen] = useState(false);
  useLocalizedValidation(form, createOpen);
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
      message.success(i18n.t('repo:feedback.added'));
      setCreateOpen(false);
      form.resetFields();
      queryClient.invalidateQueries({ queryKey: ['repos'] });
    },
    onError: (error: Error) => {
      message.error(error.message || i18n.t('repo:errors.addRepo'));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteRepo(id),
    onSuccess: () => {
      message.success(i18n.t('repo:feedback.deleted'));
      setPendingDeleteId(null);
      queryClient.invalidateQueries({ queryKey: ['repos'] });
      queryClient.invalidateQueries({ queryKey: ['repo-relations'] });
    },
    onError: (error: Error) => {
      setPendingDeleteId(null);
      message.error(error.message || i18n.t('repo:errors.deleteRepo'));
    },
  });

  const handleCreateRepo = async () => {
    await runWithAccess('READ_WRITE', t('actions.addRepo'), async () => {
      // 校验失败时 antd 已在表单项下方展示错误信息，这里吞掉拒绝避免未处理 Promise。
      const values = await form.validateFields().catch(() => null);
      if (values) {
        createMutation.mutate(values);
      }
    });
  };

  const columns: ColumnsType<Repo> = [
    { title: t('fields.id'), dataIndex: 'id', width: 70 },
    {
      title: t('fields.name'), dataIndex: 'name', align: 'left', width: 220, ellipsis: { showTitle: false },
      render: (name: string, record: Repo) => (
        <EllipsisText tooltip={name}><Link to={`/repos/${record.id}`}>{name}</Link></EllipsisText>
      ),
    },
    {
      title: t('fields.url'), dataIndex: 'url', align: 'left', width: 260, ellipsis: { showTitle: false },
      render: (v: string) => <EllipsisText>{v}</EllipsisText>,
    },
    {
      title: t('fields.description'), dataIndex: 'description', align: 'left', width: 240, ellipsis: { showTitle: false },
      render: (v: string | null) => (v ? <EllipsisText>{v}</EllipsisText> : '-'),
    },
    {
      title: t('fields.created'), dataIndex: 'gmtCreate', width: 160,
      render: (t: string) => formatDateTime(t),
    },
    {
      title: t('fields.actions'), width: 80,
      render: (_: unknown, record: Repo) => (
        <Popconfirm
          title={t('confirm.deleteRepo', { name: record.name })}
          description={t('confirm.deleteHint')}
          open={pendingDeleteId === record.id}
          onOpenChange={(open) => {
            if (!open) setPendingDeleteId(null);
          }}
          onConfirm={() => runWithAccess(
            'READ_WRITE',
            t('actions.deleteRepo'),
            () => deleteMutation.mutate(record.id),
          )}
          okText={t('actions.delete')}
          okButtonProps={{ danger: true }}
          cancelText={t('actions.cancel')}
        >
          <Button
            type="link"
            size="small"
            danger
            aria-label={t('actions.deleteRepoLabel', { name: record.name })}
            title={t('actions.deleteRepoLabel', { name: record.name })}
            icon={<DeleteOutlined aria-hidden="true" />}
            onClick={() => runWithAccess(
              'READ_WRITE',
              t('actions.deleteRepo'),
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
          <Button icon={<ShareAltOutlined aria-hidden="true" />} onClick={switchToMapTab}>{t('actions.map')}</Button>
          <Button
            type="primary"
            icon={<PlusOutlined aria-hidden="true" />}
            onClick={() => runWithAccess('READ_WRITE', t('actions.addRepo'), () => setCreateOpen(true))}
          >
            {t('actions.addRepo')}
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
        title={t('actions.addRepo')}
        open={createOpen}
        okText={t('actions.confirm')}
        cancelText={t('actions.cancel')}
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
            label={t('fields.repoName')}
            name="name"
            rules={[
              { required: true, message: t('validation.nameRequired') },
              {
                validator: (_, value) => (
                  isValidRepoName(value)
                    ? Promise.resolve()
                    : Promise.reject(new Error(repoNameInvalidMessage()))
                ),
              },
            ]}
          >
            <Input placeholder="auto-wonder" />
          </Form.Item>
          <Form.Item
            label={t('fields.repoUrl')}
            name="url"
            rules={[
              { required: true, message: t('validation.urlRequired') },
              {
                validator: (_, value) => (
                  isValidRepoUrl(value)
                    ? Promise.resolve()
                    : Promise.reject(new Error(t('validation.urlInvalid')))
                ),
              },
            ]}
          >
            <Input placeholder={t('placeholders.urlExample')} />
          </Form.Item>
          <Form.Item label={t('fields.branch')} name="defaultBranch">
            <Input placeholder="main" />
          </Form.Item>
          <Form.Item label={t('fields.description')} name="description">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
