import { useState } from 'react';
import { Tag, Button, Popconfirm, Space } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import type { StatusNode, NodeCategory } from '../types';
import { NodeFormModal } from './NodeFormModal';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const categoryStyle: Record<NodeCategory, { color: string; bg: string; border: string }> = {
  INIT: { color: '#1890ff', bg: '#e6f7ff', border: '#91d5ff' },
  IN_PROGRESS: { color: '#FF6A00', bg: '#fff7e6', border: '#ffd591' },
  DONE: { color: '#52c41a', bg: '#f6ffed', border: '#b7eb8f' },
  CANCELED: { color: '#f5222d', bg: '#fff1f0', border: '#ffa39e' },
};

const categoryLabel: Record<NodeCategory, string> = {
  INIT: '初始态', IN_PROGRESS: '进行中', DONE: '完成态', CANCELED: '取消态',
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
  const accessCommand = useAccessCommand();
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<StatusNode | null>(null);

  const openCreate = () => accessCommand('READ_WRITE', '添加状态节点', () => {
    setEditing(null);
    setModalOpen(true);
  });
  const openEdit = (node: StatusNode) => accessCommand('READ_WRITE', '编辑状态节点', () => {
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
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14 }}>状态节点</span>
        <span style={{ marginLeft: 8, fontSize: 12, color: '#999' }}>定义工单的所有可能状态</span>
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
              <span style={{ fontSize: 11, color: '#666' }}>{node.name}</span>
              <Space size={2} style={{ marginLeft: 4 }}>
                <EditOutlined style={{ fontSize: 11, color: '#1890ff', cursor: 'pointer' }} onClick={() => openEdit(node)} />
                <Popconfirm title="确认删除该状态节点？" onConfirm={() => onDelete(node.id)} okText="删除" cancelText="取消">
                  <DeleteOutlined style={{ fontSize: 11, color: '#ff4d4f', cursor: 'pointer' }} />
                </Popconfirm>
              </Space>
            </Tag>
          );
        })}
        <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={openCreate}>添加节点</Button>
      </div>
      <div style={{ marginTop: 8, fontSize: 11, color: '#999' }}>
        {Object.entries(categoryLabel).map(([cat, label]) => (
          <span key={cat} style={{ marginRight: 12 }}>
            <span style={{ color: categoryStyle[cat as NodeCategory].color }}>●</span> {label}
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
