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
import { MemoryTopicEditor, memoryTypeLabels } from './MemoryTopicEditor';
import { MemoryAclPanel } from './MemoryAclPanel';
import { MemoryImportSourcesPanel } from './MemoryImportSourcesPanel';

const scopeLabel = { ORG: '组织共享', SQUAD: '小队共享', AGENT: '数字人个人' } as const;
const ownerKey = (owner: MemoryOwner) => `${owner.scope}:${owner.ownerRef}`;

export function MemoryListPage() {
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
    await runWithAccess('READ_WRITE', '保存记忆', async () => {
      await saveTopic.mutateAsync({ target, topic }); setSelected(ownerKey(target)); setEditing(undefined); message.success('记忆已保存');
    });
  };
  const saveIndex = async (request: MemoryMutation) => { await runWithAccess('READ_WRITE', '保存索引', async () => { await indexMutation.mutateAsync(request); setEditingIndex(undefined); message.success('索引已保存'); }); };
  if (isLoading) return <Spin />;
  if (isError) return <Alert type="error" showIcon message="记忆目录加载失败，请刷新重试" />;
  return <div>
    <PageHeading title="记忆"
      extra={<Button type="primary" icon={<PlusOutlined />} disabled={!writable} onClick={() => runWithAccess('READ_WRITE', '新增记忆', () => setEditing(null))}>新增记忆</Button>} />
    {directory?.migration && <Alert style={{ marginBottom: 16 }} showIcon type={directory.migration.pending ? 'warning' : 'info'}
      message={`历史记忆迁移：已迁移 ${directory.migration.migrated} 条，待迁移 ${directory.migration.pending} 条`}
      description={directory.migration.pending ? '后台会自动继续迁移和重试；原始记忆仍然保留。迁移数量不代表索引容量或召回效果。' : undefined} />}
    <Row gutter={16}>
      <Col xs={24} md={7} lg={6}><Card title="记忆归属" styles={{ body: { padding: 8 } }}>
        <List dataSource={owners} locale={{ emptyText: '暂无可用归属' }} renderItem={item => <List.Item onClick={() => setSelected(ownerKey(item))}
          style={{ cursor: 'pointer', background: owner && ownerKey(item) === ownerKey(owner) ? 'rgba(var(--aw-accent-rgb), .10)' : undefined, padding: 12 }}>
          <List.Item.Meta title={item.name} description={`${item.squadNames?.length ? item.squadNames.join('、') + ' · ' : ''}${scopeLabel[item.scope]}${item.ownerRef ? ' · #' + item.ownerRef : ''}`} />
        </List.Item>} />
      </Card></Col>
      <Col xs={24} md={17} lg={18}>{!owner ? <Empty description="请选择记忆归属" /> : storeId == null ?
        <Empty description={writable ? '暂无记忆，可通过“新增记忆”添加' : '暂无可查看的记忆'} /> : <>
        <Typography.Paragraph type="secondary">记忆库修订版本 {owner.currentRevision ?? 0}</Typography.Paragraph>
        <Tabs items={[
          { key: 'topics', label: `记忆内容 (${topics.length})`, children: documentsLoading ? <Spin /> : <>
            {index && <Card size="small" title="记忆索引" style={{ marginBottom: 12 }} extra={<Button type="link" onClick={() => setEditingIndex(index)}>编辑索引</Button>}>
              <Button type="link" onClick={() => setViewing(index)}>MEMORY.md</Button>
              <Typography.Text type="secondary">记录有哪些记忆、何时值得读取；详细内容保存在下方主题中。</Typography.Text>
            </Card>}
            <List dataSource={topics} locale={{ emptyText: '暂无记忆内容' }} pagination={{
              current: docPage, pageSize: docPageSize, total: topics.length,
              showSizeChanger: true, showTotal: (t) => `共 ${t} 条`,
              onChange: (p, ps) => { setDocPage(p); if (ps !== docPageSize) setDocPageSize(ps); },
            }} renderItem={document => <List.Item actions={[
              <Button key="edit" type="link" disabled={!writable} icon={<EditOutlined />} onClick={() => setEditing(document)}>编辑</Button>,
              <Popconfirm key="delete" title="删除此记忆及其索引引用？" disabled={!writable} onConfirm={() => runWithAccess('READ_WRITE', '删除记忆', () => removeTopic.mutateAsync(document))}><Button type="link" danger disabled={!writable} icon={<DeleteOutlined />}>删除</Button></Popconfirm>,
            ]}><List.Item.Meta title={<Button type="link" style={{ padding: 0 }} onClick={() => setViewing(document)}>{document.title ?? document.path}</Button>}
              description={`${document.description ?? ''} · ${document.byteSize.toLocaleString()} 字节 · 文件版本 ${document.version}`} /></List.Item>} />
          </> },
          { key: 'history', label: '变更历史', children: <List dataSource={history} locale={{ emptyText: '暂无变更' }} pagination={{
            current: historyPage, pageSize: historyPageSize, total: history.length,
            showSizeChanger: true, showTotal: (t) => `共 ${t} 条`,
            onChange: (p, ps) => { setHistoryPage(p); if (ps !== historyPageSize) setHistoryPageSize(ps); },
          }} renderItem={change => <List.Item><Typography.Text>{change.operation} · {change.path}</Typography.Text><Typography.Text type="secondary">记忆库修订版本 {change.storeRevision}</Typography.Text></List.Item>} /> },
          { key: 'acl', label: '访问权限', children: <MemoryAclPanel storeId={storeId} administrator={administrator} /> },
          { key: 'imports', label: '本地来源', children: owner.scope === 'AGENT' ? <MemoryImportSourcesPanel administrator={administrator} agentId={owner.ownerRef} /> : <Alert type="info" message="本地来源属于具体数字人的个人记忆，请选择数字人查看。" /> },
        ]} />
      </>}</Col>
    </Row>
    <MemoryTopicEditor open={editing !== undefined} owners={owners} initialOwner={owner} document={editing ?? undefined} saving={saveTopic.isPending} onCancel={() => setEditing(undefined)} onSave={save} />
    <MemoryDocumentEditor open={!!editingIndex} document={editingIndex} writable={writable} saving={indexMutation.isPending} onCancel={() => setEditingIndex(undefined)} onSave={saveIndex} />
    <Modal title={viewing?.title ?? viewing?.path} open={!!viewing} onCancel={() => setViewing(undefined)} footer={null} width={760}>
      <Typography.Paragraph type="secondary">{viewing?.path} · 文件版本 {viewing?.version}{viewing?.memoryType ? ' · ' + (memoryTypeLabels[viewing.memoryType as keyof typeof memoryTypeLabels] ?? viewing.memoryType) : ''}</Typography.Paragraph>
      <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>{viewing?.body ?? viewing?.contentMd}</Typography.Paragraph>
    </Modal>
  </div>;
}
