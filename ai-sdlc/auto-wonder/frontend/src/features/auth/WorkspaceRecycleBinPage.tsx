import { useState, type CSSProperties } from 'react';
import { Alert, Button, Empty, Input, Modal, Pagination, Spin, Table, Tag, Tooltip, Typography, message } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate } from 'react-router-dom';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import type { RecycleBinItem } from '@/shared/types/common';
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue';
import { HelpCenterLink } from '@/shared/ui/HelpCenterLink';
import { useRestoreWorkspace, useRecycleBin } from './workspaceLifecycleApi';
import './workspaceLifecycle.css';

const { Title, Text } = Typography;

const PAGE_SIZE = 20;
const KEYWORD_DEBOUNCE_MS = 300;

export function WorkspaceRecycleBinPage() {
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [restoreTargetId, setRestoreTargetId] = useState<number | null>(null);
  const debouncedKeyword = useDebouncedValue(keyword, KEYWORD_DEBOUNCE_MS);
  const navigate = useNavigate();

  const { data, isLoading, isFetching } = useRecycleBin(debouncedKeyword, page, PAGE_SIZE);
  const items = data?.list ?? [];
  const total = data?.total ?? 0;

  // Derived from the live page, never snapshotted: a refetch can flip `restorable` when somebody
  // else creates a workspace with the same name while this dialog is open.
  const restoreTarget = restoreTargetId === null
    ? null
    : items.find((item) => item.id === restoreTargetId) ?? null;

  const handleKeywordChange = (value: string) => {
    setKeyword(value);
    // Without this a user on page 3 who narrows the keyword lands on an empty page.
    setPage(1);
  };

  const columns: ColumnsType<RecycleBinItem> = [
    {
      title: '名称',
      dataIndex: 'name',
      width: 200,
      render: (name: string) => <Text strong>{name}</Text>,
    },
    {
      title: '描述',
      dataIndex: 'description',
      ellipsis: true,
      render: (description: string | null) => description || <Text type="secondary">暂无描述</Text>,
    },
    {
      title: '原 Owner',
      dataIndex: 'ownerName',
      width: 140,
      render: (ownerName: string | null) => ownerName || '-',
    },
    {
      title: '删除时间',
      dataIndex: 'deletedAt',
      width: 180,
      // F4.4: the server already orders by deleted_at DESC; this only renders it.
      render: (deletedAt: string | null) => (deletedAt ? new Date(deletedAt).toLocaleString('zh-CN') : '-'),
    },
    {
      title: '删除人',
      dataIndex: 'deletedByName',
      width: 140,
      render: (deletedByName: string | null) => deletedByName || '-',
    },
    {
      title: '可恢复状态',
      dataIndex: 'restorable',
      width: 140,
      render: (restorable: boolean | null) => (restorable === false ? (
        <Tooltip title="已存在同名的在用工作空间，恢复时需要改名">
          <Tag color="orange">需改名恢复</Tag>
        </Tooltip>
      ) : (
        <Tag color="green">可恢复</Tag>
      )),
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_: unknown, record: RecycleBinItem) => (
        <Button
          type="link"
          size="small"
          data-testid={`restore-workspace-${record.id}`}
          onClick={() => setRestoreTargetId(record.id)}
        >
          恢复
        </Button>
      ),
    },
  ];

  return (
    <div style={pageShellStyle}>
      <div style={contentStyle}>
        <div style={headerStyle}>
          <div>
            <Title level={3} style={{ margin: 0, color: '#111827' }}>工作空间回收站</Title>
            <Text style={{ display: 'block', marginTop: 6, color: '#697386' }}>
              仅展示你有权管理（原 Owner、原管理员或平台管理员）的已删除工作空间
            </Text>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <HelpCenterLink />
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/workspaces')}>
              返回工作空间列表
            </Button>
          </div>
        </div>

        <div style={toolbarStyle}>
          <Input
            aria-label="搜索已删除的工作空间"
            placeholder="按名称搜索"
            allowClear
            value={keyword}
            onChange={(event) => handleKeywordChange(event.target.value)}
            style={{ maxWidth: 320 }}
          />
          <Text style={{ color: '#697386' }}>共 {total} 个已删除工作空间</Text>
        </div>

        {isLoading && !data ? (
          <div data-testid="recycle-bin-loading" style={{ textAlign: 'center', padding: 48 }}><Spin /></div>
        ) : items.length === 0 ? (
          <div data-testid="recycle-bin-empty" style={emptyStateStyle}>
            <Empty
              description={debouncedKeyword.trim()
                ? `没有匹配「${debouncedKeyword.trim()}」的已删除工作空间`
                : '回收站是空的'}
            />
          </div>
        ) : (
          <Table<RecycleBinItem>
            rowKey="id"
            size="middle"
            loading={isFetching}
            columns={columns}
            dataSource={items}
            pagination={false}
          />
        )}

        {total > PAGE_SIZE && (
          <div style={{ marginTop: 20, textAlign: 'right' }}>
            <Pagination
              current={page}
              pageSize={PAGE_SIZE}
              total={total}
              showSizeChanger={false}
              onChange={setPage}
            />
          </div>
        )}
      </div>

      <RestoreWorkspaceModal
        target={restoreTarget}
        onClose={() => setRestoreTargetId(null)}
      />
    </div>
  );
}

interface RestoreWorkspaceModalProps {
  target: RecycleBinItem | null;
  onClose: () => void;
}

function RestoreWorkspaceModal({ target, onClose }: RestoreWorkspaceModalProps) {
  const [newName, setNewName] = useState('');
  const { mutateAsync, isPending } = useRestoreWorkspace();
  // F5.5: a name taken by an in-use workspace blocks a plain restore, so the rename field is
  // the way out and the dialog says so up front instead of after a failed submit.
  const needsRename = target?.restorable === false;

  const handleRestore = async () => {
    if (!target) return;
    const trimmed = newName.trim();
    if (needsRename && !trimmed) {
      message.error('已存在同名的在用工作空间，请输入新名称后再恢复');
      return;
    }
    try {
      await mutateAsync({ id: target.id, newName: trimmed || null });
      message.success(`工作空间「${trimmed || target.name}」已恢复，请重新进入`);
      setNewName('');
      onClose();
    } catch (e) {
      if (e instanceof ApiError && e.code === ErrorCodes.ORG_RESTORE_NAME_CONFLICT) {
        message.error('已存在同名的在用工作空间，请改名后再恢复');
        return;
      }
      message.error(e instanceof ApiError ? e.message : '恢复失败，请稍后重试');
    }
  };

  return (
    <Modal
      title={target ? `恢复「${target.name}」` : ''}
      open={target !== null}
      okText="确认恢复"
      cancelText="取消"
      confirmLoading={isPending}
      onCancel={() => { setNewName(''); onClose(); }}
      onOk={handleRestore}
      destroyOnHidden
      // Reset the rename field whenever a different row is opened: the component instance
      // survives between openings, so state alone would carry the last name across.
      afterOpenChange={(open) => { if (!open) setNewName(''); }}
    >
      <Text style={{ display: 'block', marginBottom: 12, color: '#374151' }}>
        恢复后成员关系、访问级别与工作空间内的业务数据保持不变；被暂停的定时任务不会自动恢复，需要手动重新启用。
      </Text>
      {needsRename && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="已存在同名的在用工作空间"
          description="请输入一个新名称，将以新名称恢复该工作空间。"
        />
      )}
      <Input
        aria-label="恢复后的工作空间名称"
        placeholder={needsRename ? '必填：输入恢复后的新名称' : '选填：输入恢复后的新名称，留空则沿用原名称'}
        maxLength={128}
        value={newName}
        onChange={(event) => setNewName(event.target.value)}
      />
    </Modal>
  );
}

const pageShellStyle: CSSProperties = {
  minHeight: '100vh',
  padding: '32px 24px',
  background: '#f9fafb',
};

const contentStyle: CSSProperties = {
  width: 'min(1120px, 100%)',
  margin: '0 auto',
  background: '#fff',
  border: '1px solid #e5e7eb',
  borderRadius: 8,
  padding: 24,
};

const headerStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: 24,
  marginBottom: 20,
  flexWrap: 'wrap',
};

const toolbarStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 16,
  marginBottom: 16,
  flexWrap: 'wrap',
};

const emptyStateStyle: CSSProperties = {
  border: '1px dashed #fed7aa',
  background: '#fff',
  borderRadius: 8,
  padding: 32,
};
