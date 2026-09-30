import { useState } from 'react';
import { Button, Popconfirm, Alert, Space } from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import type { StatusNode, StatusTransition, NodeCategory } from '../types';
import { TransitionFormModal } from './TransitionFormModal';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const categoryBorder: Record<string, string> = {
  INIT: 'color-mix(in srgb, var(--aw-info) 30%, var(--aw-border))', IN_PROGRESS: 'color-mix(in srgb, var(--aw-warning) 30%, var(--aw-border))', DONE: 'color-mix(in srgb, var(--aw-success) 30%, var(--aw-border))', CANCELED: 'color-mix(in srgb, var(--aw-error) 30%, var(--aw-border))',
};
const categoryBg: Record<string, string> = {
  INIT: 'color-mix(in srgb, var(--aw-info) 10%, var(--aw-panel))', IN_PROGRESS: 'color-mix(in srgb, var(--aw-warning) 10%, var(--aw-panel))', DONE: 'color-mix(in srgb, var(--aw-success) 10%, var(--aw-panel))', CANCELED: 'color-mix(in srgb, var(--aw-error) 10%, var(--aw-panel))',
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
    if (!node) return <span style={{ padding: '2px 8px', background: 'var(--aw-raised)', border: '1px solid var(--aw-border)', borderRadius: 4, fontSize: 11 }}>?</span>;
    const cat = node.category as NodeCategory;
    return (
      <span style={{ padding: '2px 8px', background: categoryBg[cat] || 'var(--aw-raised)', border: `1px solid ${categoryBorder[cat] || 'var(--aw-border)'}`, borderRadius: 4, fontSize: 11 }}>
        {node.code}
      </span>
    );
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14 }}>推荐流转 (快捷操作)</span>
        <span style={{ marginLeft: 8, fontSize: 12, color: 'var(--aw-muted)' }}>定义工单页面的快捷状态变更按钮</span>
        <Button icon={<PlusOutlined />} onClick={openCreate} style={{ marginLeft: 'auto' }}>添加推荐流转</Button>
      </div>
      <Table<StatusTransition>
        rowKey="id"
        dataSource={transitions}
        pagination={false}
        scroll={{ x: 'max-content' }}
        columns={[
          { title: '起始状态', dataIndex: 'fromNodeId', render: renderNodeTag },
          { title: '目标状态', dataIndex: 'toNodeId', render: renderNodeTag },
          { title: '操作名称', dataIndex: 'name', align: 'left' },
          { title: '操作', width: 180, render: (_, tr) => (
            <Space>
              <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(tr)}>编辑</Button>
              <Popconfirm title="确认删除该流转？" onConfirm={() => onDelete(tr.id)} okText="删除" cancelText="取消">
                <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
              </Popconfirm>
            </Space>
          ) },
        ]}
      />
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
