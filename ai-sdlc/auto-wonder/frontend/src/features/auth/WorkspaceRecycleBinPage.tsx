import { i18n } from '@/shared/i18n';
import { formatDateTime } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { Table } from '@/shared/theme/ThemedTable';
import { useState, type CSSProperties } from 'react';
import { Alert, Button, Empty, Input, Modal, Pagination, Spin, Tag, Tooltip, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import type { RecycleBinItem } from '@/shared/types/common';
import { useDebouncedValue } from '@/shared/hooks/useDebouncedValue';
import { PageHeader } from '@/shared/ui/PageHeader';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { useRestoreWorkspace, useRecycleBin } from './workspaceLifecycleApi';
import './workspaceLifecycle.css';

const { Title, Text } = Typography;

const PAGE_SIZE = 20;
const KEYWORD_DEBOUNCE_MS = 300;

export function WorkspaceRecycleBinPage() {
  const { t } = useTranslation('auth');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [restoreTargetId, setRestoreTargetId] = useState<number | null>(null);
  const debouncedKeyword = useDebouncedValue(keyword, KEYWORD_DEBOUNCE_MS);

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
      title: t('recycle.name'),
      dataIndex: 'name',
      width: 200,
      ellipsis: { showTitle: false },
      render: (name: string) => <EllipsisText tooltip={name}><Text strong>{name}</Text></EllipsisText>,
    },
    {
      title: t('recycle.description'),
      dataIndex: 'description',
      align: 'left',
      ellipsis: { showTitle: false },
      render: (description: string | null) => (description
        ? <EllipsisText>{description}</EllipsisText>
        : <Text type="secondary">{t('workspace.noDescription')}</Text>),
    },
    {
      title: t('recycle.owner'),
      dataIndex: 'ownerName',
      width: 140,
      ellipsis: { showTitle: false },
      render: (ownerName: string | null) => <EllipsisText>{ownerName || '-'}</EllipsisText>,
    },
    {
      title: t('recycle.deletedAt'),
      dataIndex: 'deletedAt',
      width: 180,
      // F4.4: the server already orders by deleted_at DESC; this only renders it.
      render: (deletedAt: string | null) => (deletedAt ? formatDateTime(deletedAt, { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric', hour12: false }) : '-'),
    },
    {
      title: t('recycle.deletedBy'),
      dataIndex: 'deletedByName',
      width: 140,
      ellipsis: { showTitle: false },
      render: (deletedByName: string | null) => <EllipsisText>{deletedByName || '-'}</EllipsisText>,
    },
    {
      title: t('recycle.status'),
      dataIndex: 'restorable',
      width: 140,
      render: (restorable: boolean | null) => (restorable === false ? (
        <Tooltip title={t('recycle.nameConflictHint')}>
          <Tag color="orange">{t('recycle.renameRequired')}</Tag>
        </Tooltip>
      ) : (
        <Tag color="green">{t('recycle.restorable')}</Tag>
      )),
    },
    {
      title: t('recycle.actions'),
      key: 'action',
      width: 100,
      render: (_: unknown, record: RecycleBinItem) => (
        <Button
          type="link"
          size="small"
          data-testid={`restore-workspace-${record.id}`}
          onClick={() => setRestoreTargetId(record.id)}
        >{t('recycle.restore')}</Button>
      ),
    },
  ];

  return (
    <div style={pageShellStyle}>
      <PageHeader brandTo="/workspaces" />
      <div style={contentStyle}>
        <div style={headerStyle}>
          <div>
            <Title level={3} style={{ margin: 0, color: 'var(--aw-text)' }}>{t('workspace.recycleBin')}</Title>
            <Text style={{ display: 'block', marginTop: 6, color: 'var(--aw-muted)' }}>{t('recycle.scope')}</Text>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <PageBackButton to="/workspaces" label={t('recycle.back')} compact={false} />
          </div>
        </div>

        <div style={toolbarStyle}>
          <Input
            aria-label={t('recycle.search')}
            placeholder={t('recycle.searchPlaceholder')}
            allowClear
            value={keyword}
            onChange={(event) => handleKeywordChange(event.target.value)}
            style={{ maxWidth: 320 }}
          />
          <Text style={{ color: 'var(--aw-muted)' }}>{t('recycle.count', { count: total })}</Text>
        </div>

        {isLoading && !data ? (
          <div data-testid="recycle-bin-loading" style={{ textAlign: 'center', padding: 48 }}><Spin /></div>
        ) : items.length === 0 ? (
          <div data-testid="recycle-bin-empty" style={emptyStateStyle}>
            <Empty
              description={debouncedKeyword.trim()
                ? t('recycle.noMatches', { keyword: debouncedKeyword.trim() })
                : t('recycle.empty')}
            />
          </div>
        ) : (
          <Table<RecycleBinItem>
            rowKey="id"
            size="middle"
            loading={isFetching}
            columns={columns}
            scroll={{ x: 1120 }}
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
  const { t } = useTranslation('auth');
  const [newName, setNewName] = useState('');
  const { mutateAsync, isPending } = useRestoreWorkspace();
  // F5.5: a name taken by an in-use workspace blocks a plain restore, so the rename field is
  // the way out and the dialog says so up front instead of after a failed submit.
  const needsRename = target?.restorable === false;

  const handleRestore = async () => {
    if (!target) return;
    const trimmed = newName.trim();
    if (needsRename && !trimmed) {
      message.error(i18n.t('auth:recycle.newNameRequired'));
      return;
    }
    try {
      await mutateAsync({ id: target.id, newName: trimmed || null });
      message.success(i18n.t('auth:recycle.restored', { name: trimmed || target.name }));
      setNewName('');
      onClose();
    } catch (e) {
      if (e instanceof ApiError && e.code === ErrorCodes.ORG_RESTORE_NAME_CONFLICT) {
        message.error(i18n.t('auth:recycle.nameConflict'));
        return;
      }
      message.error(e instanceof ApiError ? e.message : i18n.t('auth:recycle.failed'));
    }
  };

  return (
    <Modal
      title={target ? t('recycle.restoreTitle', { name: target.name }) : ''}
      open={target !== null}
      okText={t('recycle.confirm')}
      cancelText={t('action.cancel')}
      confirmLoading={isPending}
      onCancel={() => { setNewName(''); onClose(); }}
      onOk={handleRestore}
      destroyOnHidden
      // Reset the rename field whenever a different row is opened: the component instance
      // survives between openings, so state alone would carry the last name across.
      afterOpenChange={(open) => { if (!open) setNewName(''); }}
    >
      <Text style={{ display: 'block', marginBottom: 12, color: 'var(--aw-text)' }}>{t('recycle.consequences')}</Text>
      {needsRename && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={t('recycle.conflict')}
          description={t('recycle.renameHint')}
        />
      )}
      <Input
        aria-label={t('recycle.restoredName')}
        placeholder={needsRename ? t('recycle.requiredPlaceholder') : t('recycle.optionalPlaceholder')}
        maxLength={128}
        value={newName}
        onChange={(event) => setNewName(event.target.value)}
      />
    </Modal>
  );
}

const pageShellStyle: CSSProperties = {
  minHeight: '100vh',
  background: 'var(--aw-bg)',
};

const contentStyle: CSSProperties = {
  margin: 24,
  minWidth: 0,
  background: 'var(--aw-panel)',
  border: '1px solid var(--aw-border)',
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
  border: '1px dashed var(--aw-border)',
  background: 'var(--aw-panel)',
  borderRadius: 8,
  padding: 32,
};
