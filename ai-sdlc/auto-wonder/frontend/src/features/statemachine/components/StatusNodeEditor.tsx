import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { Tag, Button, Popconfirm, Space } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import type { StatusNode, NodeCategory } from '../types';
import { NodeFormModal } from './NodeFormModal';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const categoryStyle: Record<NodeCategory, { color: string; bg: string; border: string }> = {
  INIT: { color: 'var(--aw-info)', bg: 'color-mix(in srgb, var(--aw-info) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-info) 30%, var(--aw-border))' },
  IN_PROGRESS: { color: 'var(--aw-warning)', bg: 'color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-warning) 30%, var(--aw-border))' },
  DONE: { color: 'var(--aw-success)', bg: 'color-mix(in srgb, var(--aw-success) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-success) 30%, var(--aw-border))' },
  CANCELED: { color: 'var(--aw-error)', bg: 'color-mix(in srgb, var(--aw-error) 10%, var(--aw-panel))', border: 'color-mix(in srgb, var(--aw-error) 30%, var(--aw-border))' },
};

interface Props {
  nodes: StatusNode[];
  onCreate: (data: { code: string; name: string; category: string; sort: number }) => void;
  onUpdate: (nodeId: number, data: { code: string; name: string; category: string; sort: number }) => void;
  onDelete: (nodeId: number) => void;
  createLoading?: boolean;
  updateLoading?: boolean;
}

export function StatusNodeEditor({ nodes, onCreate, onUpdate, onDelete, createLoading, updateLoading }: Props) {
  const { t } = useTranslation('statemachine');
  const categoryLabel: Record<NodeCategory, string> = {
    INIT: t('categories.INIT'), IN_PROGRESS: t('categories.IN_PROGRESS'), DONE: t('categories.DONE'), CANCELED: t('categories.CANCELED'),
  };
  const accessCommand = useAccessCommand();
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<StatusNode | null>(null);

  const openCreate = () => accessCommand('READ_WRITE', t('actions.createNode'), () => {
    setEditing(null);
    setModalOpen(true);
  });
  const openEdit = (node: StatusNode) => accessCommand('READ_WRITE', t('actions.editNode'), () => {
    setEditing(node);
    setModalOpen(true);
  });

  const handleSubmit = (values: { code: string; name: string; category: string; sort: number }) => {
    if (editing) {
      onUpdate(editing.id, values);
    } else {
      onCreate(values);
    }
    setModalOpen(false);
  };

  const nextSort = nodes.length > 0 ? Math.max(...nodes.map((n) => n.sort)) + 1 : 0;

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14 }}>{t('nodes.title')}</span>
        <span style={{ marginLeft: 8, fontSize: 12, color: 'var(--aw-muted)' }}>{t('nodes.help')}</span>
        <Button icon={<PlusOutlined aria-hidden="true" />} onClick={openCreate} style={{ marginLeft: 'auto' }}>{t('nodes.add')}</Button>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
        {nodes.map((node) => {
          const style = categoryStyle[node.category as NodeCategory] || categoryStyle.IN_PROGRESS;
          return (
            <Tag
              key={node.id}
              style={{ padding: '4px 10px', background: style.bg, border: `1px solid ${style.border}`, borderRadius: 6, cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6 }}
            >
              <span style={{ width: 8, height: 8, borderRadius: '50%', background: style.color, display: 'inline-block' }} />
              <span style={{ fontWeight: 500 }}>{node.code}</span>
              <span style={{ fontSize: 11, color: 'var(--aw-muted)' }}>{node.name}</span>
              <Space size={2} style={{ marginLeft: 4 }}>
                <Button type="text" size="small" aria-label={t('nodes.editLabel', { code: node.code })} icon={<EditOutlined aria-hidden="true" />} style={{ color: 'var(--aw-info)' }} onClick={() => openEdit(node)} />
                <Popconfirm title={t('nodes.confirmDelete')} onConfirm={() => onDelete(node.id)} okText={t('common.delete')} cancelText={t('common.cancel')}>
                  <Button type="text" size="small" danger aria-label={t('nodes.deleteLabel', { code: node.code })} icon={<DeleteOutlined aria-hidden="true" />} />
                </Popconfirm>
              </Space>
            </Tag>
          );
        })}
      </div>
      <div style={{ marginTop: 8, fontSize: 11, color: 'var(--aw-muted)' }}>
        {Object.entries(categoryLabel).map(([cat, label]) => (
          <span key={cat} style={{ marginRight: 12 }}>
            <span aria-hidden="true" style={{ color: categoryStyle[cat as NodeCategory].color }}>●</span> {label}
          </span>
        ))}
      </div>
      <NodeFormModal
        open={modalOpen}
        editing={editing}
        nextSort={nextSort}
        onSubmit={handleSubmit}
        onCancel={() => setModalOpen(false)}
        loading={createLoading || updateLoading}
      />
    </div>
  );
}
