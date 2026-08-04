import { useState } from 'react';
import { Button, Popconfirm, Alert } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import type { StatusNode, StatusTransition, NodeCategory } from '../types';
import { TransitionFormModal } from './TransitionFormModal';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const categoryBorder: Record<string, string> = {
  INIT: '#91d5ff', IN_PROGRESS: '#ffd591', DONE: '#b7eb8f', CANCELED: '#ffa39e',
};
const categoryBg: Record<string, string> = {
  INIT: '#e6f7ff', IN_PROGRESS: '#fff7e6', DONE: '#f6ffed', CANCELED: '#fff1f0',
};

interface Props {
  transitions: StatusTransition[];
  nodes: StatusNode[];
  onCreate: (data: { fromNodeId: number; toNodeId: number; name: string }) => void;
  onUpdate: (tid: number, data: { fromNodeId: number; toNodeId: number; name: string }) => void;
  onDelete: (tid: number) => void;
  createLoading?: boolean;
  updateLoading?: boolean;
}

export function TransitionEditor({ transitions, nodes, onCreate, onUpdate, onDelete, createLoading, updateLoading }: Props) {
  const accessCommand = useAccessCommand();
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<StatusTransition | null>(null);

  const nodeMap = Object.fromEntries(nodes.map((n) => [n.id, n]));

  const openCreate = () => accessCommand('READ_WRITE', '添加状态流转', () => {
    setEditing(null);
    setModalOpen(true);
  });
  const openEdit = (tr: StatusTransition) => accessCommand('READ_WRITE', '编辑状态流转', () => {
    setEditing(tr);
    setModalOpen(true);
  });

  const handleSubmit = (values: { fromNodeId: number; toNodeId: number; name: string }) => {
    if (editing) {
      onUpdate(editing.id, values);
    } else {
      onCreate(values);
    }
    setModalOpen(false);
  };

  const renderNodeTag = (nodeId: number) => {
    const node = nodeMap[nodeId];
    if (!node) return <span style={{ padding: '2px 8px', background: '#f5f5f5', border: '1px solid #d9d9d9', borderRadius: 4, fontSize: 11 }}>?</span>;
    const cat = node.category as NodeCategory;
    return (
      <span style={{ padding: '2px 8px', background: categoryBg[cat] || '#f5f5f5', border: `1px solid ${categoryBorder[cat] || '#d9d9d9'}`, borderRadius: 4, fontSize: 11 }}>
        {node.code}
      </span>
    );
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14 }}>推荐流转 (快捷操作)</span>
        <span style={{ marginLeft: 8, fontSize: 12, color: '#999' }}>定义工单页面的快捷状态变更按钮</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {transitions.map((tr) => (
          <div key={tr.id} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px', background: '#fafafa', borderRadius: 6, border: '1px solid #f0f0f0' }}>
            {renderNodeTag(tr.fromNodeId)}
            <span style={{ color: '#999' }}>→</span>
            {renderNodeTag(tr.toNodeId)}
            <span style={{ marginLeft: 12, fontSize: 12, color: '#333' }}>{tr.name}</span>
            <span style={{ marginLeft: 'auto', fontSize: 11, color: '#1890ff', cursor: 'pointer' }} onClick={() => openEdit(tr)}>
              <EditOutlined /> 编辑
            </span>
            <Popconfirm title="确认删除该流转？" onConfirm={() => onDelete(tr.id)} okText="删除" cancelText="取消">
              <span style={{ fontSize: 11, color: '#ff4d4f', cursor: 'pointer' }}><DeleteOutlined /> 删除</span>
            </Popconfirm>
          </div>
        ))}
        <Button type="dashed" block icon={<PlusOutlined />} onClick={openCreate} style={{ borderRadius: 6 }}>添加推荐流转</Button>
      </div>
      <Alert
        type="warning"
        showIcon
        style={{ marginTop: 12 }}
        message="推荐流转仅定义工单页面的快捷按钮。用户始终可以手动将工单切换到任意状态。"
      />
      <TransitionFormModal
        open={modalOpen}
        editing={editing}
        nodes={nodes}
        onSubmit={handleSubmit}
        onCancel={() => setModalOpen(false)}
        loading={createLoading || updateLoading}
      />
    </div>
  );
}
