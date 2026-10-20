import { i18n } from '@/shared/i18n';
import { formatNumber } from '@/shared/i18n/format';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/shared/ui/PageHeading';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import { Alert, Button, Card, Col, Empty, List, message, Modal, Popconfirm, Row, Spin, Tabs, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@/shared/auth/store';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import type { MemoryDocument, MemoryMutation, MemoryOwner, MemoryTopicInput } from './api';
import { createMemoryTopic, deleteMemoryTopic, updateMemoryTopic } from './api';
import { useMemoryDirectory, useMemoryDocuments, useMemoryHistory, useMemoryMutation } from './hooks';
import { MemoryDocumentEditor } from './MemoryDocumentEditor';
import { MemoryTopicEditor, memoryTypeLabels, memoryOwnerName } from './MemoryTopicEditor';
import { MemoryAclPanel } from './MemoryAclPanel';
import { MemoryImportSourcesPanel } from './MemoryImportSourcesPanel';

const ownerKey = (owner: MemoryOwner) => `${owner.scope}:${owner.ownerRef}`;

export function MemoryListPage() {
  const { t } = useTranslation('memory');
  const scopeLabel = { ORG: t('scope.ORG'), SQUAD: t('scope.SQUAD'), AGENT: t('scope.AGENT') } as const;
  const operationLabels = { CREATE: t('operations.CREATE'), UPDATE: t('operations.UPDATE'), DELETE: t('operations.DELETE') };
  const description = (document: MemoryDocument) => document.descriptionKind === 'METADATA_PARSE_WARNING' ? t('document.metadataParseWarning') : document.description ?? '';
  const { data: directory, isLoading, isError } = useMemoryDirectory();
  const owners = directory?.owners ?? [];
  const [selected, setSelected] = useState<string>();
  const [editing, setEditing] = useState<MemoryDocument | null>();
  const [editingIndex, setEditingIndex] = useState<MemoryDocument>();
  const [viewing, setViewing] = useState<MemoryDocument>();
  const accessLevel = useAuthStore(state => state.accessLevel);
  const workspaceId = useAuthStore(state => state.currentWorkspace?.id);
  const client = useQueryClient();
  const runWithAccess = useAccessCommand();
  useEffect(() => { setSelected(undefined); setEditing(undefined); setEditingIndex(undefined); setViewing(undefined); }, [workspaceId]);
  const owner = owners.find(item => ownerKey(item) === selected) ?? owners.find(item => item.storeId != null) ?? owners[0];
  const storeId = owner?.storeId ?? undefined;
  const { data: documents = [], isLoading: documentsLoading } = useMemoryDocuments(storeId);
  const { data: history = [] } = useMemoryHistory(storeId);
  const indexMutation = useMemoryMutation(storeId);
  const writable = accessLevel !== 'READ_ONLY' && !!owner?.canCreate;
  const administrator = accessLevel === 'ADMIN';
  const topics = documents.filter(document => document.path !== 'MEMORY.md').sort((a, b) => a.path.localeCompare(b.path));
  const [docPage, setDocPage] = useState(1);
  const [docPageSize, setDocPageSize] = usePageSizePreference('autowonder.memory.documents.pageSize', [10, 20, 50], 10);
  const [historyPage, setHistoryPage] = useState(1);
  const [historyPageSize, setHistoryPageSize] = usePageSizePreference('autowonder.memory.history.pageSize', [10, 20, 50], 10);
  useEffect(() => { setDocPage(1); setHistoryPage(1); }, [storeId, workspaceId]);
  const index = documents.find(document => document.path === 'MEMORY.md');
  const invalidate = () => client.invalidateQueries({ queryKey: ['memory-stores'] });
  const saveTopic = useMutation({ mutationFn: async ({ target, topic }: { target: MemoryOwner; topic: MemoryTopicInput }) =>
    editing ? updateMemoryTopic(target.storeId!, topic) : createMemoryTopic(target, topic), onSuccess: invalidate });
  const removeTopic = useMutation({ mutationFn: (document: MemoryDocument) => deleteMemoryTopic(storeId!, document, `delete-${document.id}-${document.version}`), onSuccess: invalidate });
  const save = async (target: MemoryOwner, topic: MemoryTopicInput) => {
    await runWithAccess('READ_WRITE', t('commands.saveMemory'), async () => {
      await saveTopic.mutateAsync({ target, topic }); setSelected(ownerKey(target)); setEditing(undefined); message.success(i18n.t('memory:messages.memorySaved'));
    });
  };
  const saveIndex = async (request: MemoryMutation) => { await runWithAccess('READ_WRITE', t('commands.saveIndex'), async () => { await indexMutation.mutateAsync(request); setEditingIndex(undefined); message.success(i18n.t('memory:messages.indexSaved')); }); };
  if (isLoading) return <Spin />;
  if (isError) return <Alert type="error" showIcon message={t('page.loadError')} />;
  return <div>
    <PageHeading title={t('page.title')}
      extra={<Button type="primary" icon={<PlusOutlined aria-hidden="true" />} disabled={!writable} onClick={() => runWithAccess('READ_WRITE', t('page.create'), () => setEditing(null))}>{t('page.create')}</Button>} />
    {directory?.migration && <Alert style={{ marginBottom: 16 }} showIcon type={directory.migration.pending ? 'warning' : 'info'}
      message={t('migration.summary', { migrated: t('migration.migrated', { count: directory.migration.migrated }), pending: t('migration.pending', { count: directory.migration.pending }) })}
      description={directory.migration.pending ? t('migration.hint') : undefined} />}
    <Row gutter={16}>
      <Col xs={24} md={7} lg={6}><Card title={t('page.owners')} styles={{ body: { padding: 8 } }}>
        <List dataSource={owners} locale={{ emptyText: t('page.noOwners') }} renderItem={item => <List.Item onClick={() => setSelected(ownerKey(item))}
          style={{ cursor: 'pointer', background: owner && ownerKey(item) === ownerKey(owner) ? 'rgba(var(--aw-accent-rgb), .10)' : undefined, padding: 12 }}>
          <List.Item.Meta title={memoryOwnerName(item)} description={t('owner.description', { squads: item.squadNames?.length ? item.squadNames.join(t('owner.squadSeparator')) + ' · ' : '', scope: Object.prototype.hasOwnProperty.call(scopeLabel, item.scope) ? scopeLabel[item.scope] : item.scope, ownerRef: item.ownerRef ? ' · #' + item.ownerRef : '' })} />
        </List.Item>} />
      </Card></Col>
      <Col xs={24} md={17} lg={18}>{!owner ? <Empty description={t('page.selectOwner')} /> : storeId == null ?
        <Empty description={writable ? t('page.noWritableMemory') : t('page.noReadableMemory')} /> : <>
        <Typography.Paragraph type="secondary">{t('page.revision', { revision: formatNumber(owner.currentRevision ?? 0) })}</Typography.Paragraph>
        <Tabs items={[
          { key: 'topics', label: t('page.topics', { count: topics.length }), children: documentsLoading ? <Spin /> : <>
            {index && <Card size="small" title={t('page.index')} style={{ marginBottom: 12 }} extra={<Button type="link" disabled={!writable} onClick={() => setEditingIndex(index)}>{t('page.editIndex')}</Button>}>
              <Button type="link" onClick={() => setViewing(index)}>MEMORY.md</Button>
              <Typography.Text type="secondary">{t('page.indexHint')}</Typography.Text>
            </Card>}
            <List dataSource={topics} locale={{ emptyText: t('page.noTopics') }} pagination={{
              current: docPage, pageSize: docPageSize, total: topics.length,
              showSizeChanger: true, showTotal: (count) => t('counts.total', { count }),
              onChange: (p, ps) => { setDocPage(p); if (ps !== docPageSize) setDocPageSize(ps); },
            }} renderItem={document => <List.Item actions={[
              <Button key="edit" type="link" disabled={!writable} icon={<EditOutlined aria-hidden="true" />} onClick={() => setEditing(document)}>{t('actions.edit')}</Button>,
              <Popconfirm okText={t('actions.delete')} cancelText={t('actions.cancel')} key="delete" title={t('page.deleteConfirm')} disabled={!writable} onConfirm={() => runWithAccess('READ_WRITE', t('commands.deleteMemory'), () => removeTopic.mutateAsync(document))}><Button type="link" danger disabled={!writable} icon={<DeleteOutlined aria-hidden="true" />}>{t('actions.delete')}</Button></Popconfirm>,
            ]}><List.Item.Meta title={<Button type="link" style={{ padding: 0 }} onClick={() => setViewing(document)}>{document.title ?? document.path}</Button>}
              description={t('document.summary', { description: description(document), bytes: t('counts.bytes', { count: document.byteSize, value: formatNumber(document.byteSize) }), version: t('document.version', { version: formatNumber(document.version) }) })} /></List.Item>} />
          </> },
          { key: 'history', label: t('page.history'), children: <List dataSource={history} locale={{ emptyText: t('page.noHistory') }} pagination={{
            current: historyPage, pageSize: historyPageSize, total: history.length,
            showSizeChanger: true, showTotal: (count) => t('counts.total', { count }),
            onChange: (p, ps) => { setHistoryPage(p); if (ps !== historyPageSize) setHistoryPageSize(ps); },
          }} renderItem={change => <List.Item><Typography.Text>{Object.prototype.hasOwnProperty.call(operationLabels, change.operation) ? operationLabels[change.operation as keyof typeof operationLabels] : change.operation} · {change.path}</Typography.Text><Typography.Text type="secondary">{t('page.revision', { revision: formatNumber(change.storeRevision) })}</Typography.Text></List.Item>} /> },
          { key: 'acl', label: t('page.acl'), children: <MemoryAclPanel storeId={storeId} administrator={administrator} /> },
          { key: 'imports', label: t('page.imports'), children: owner.scope === 'AGENT' ? <MemoryImportSourcesPanel administrator={administrator} agentId={owner.ownerRef} /> : <Alert type="info" message={t('page.agentImports')} /> },
        ]} />
      </>}</Col>
    </Row>
    <MemoryTopicEditor open={editing !== undefined} owners={owners} initialOwner={owner} document={editing ?? undefined} saving={saveTopic.isPending} onCancel={() => setEditing(undefined)} onSave={save} />
    <MemoryDocumentEditor open={!!editingIndex} document={editingIndex} writable={writable} saving={indexMutation.isPending} onCancel={() => setEditingIndex(undefined)} onSave={saveIndex} />
    <Modal closable={{ 'aria-label': t('actions.close') }} title={viewing?.title ?? viewing?.path} open={!!viewing} onCancel={() => setViewing(undefined)} footer={null} width={760}>
      <Typography.Paragraph type="secondary">{t('document.viewer', { path: viewing?.path ?? '', version: viewing ? formatNumber(viewing.version) : '—' })}{viewing?.memoryType ? ' · ' + (Object.prototype.hasOwnProperty.call(memoryTypeLabels, viewing.memoryType) ? memoryTypeLabels[viewing.memoryType as keyof typeof memoryTypeLabels] : viewing.memoryType) : ''}</Typography.Paragraph>
      <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>{viewing?.body ?? viewing?.contentMd}</Typography.Paragraph>
    </Modal>
  </div>;
}
