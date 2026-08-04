import { Select, Button, Space, Tag, Modal, Form, Input, Popconfirm } from 'antd';
import { PlusOutlined, StarOutlined, DeleteOutlined } from '@ant-design/icons';
import type { StatusTemplate, WorkType } from '../types';
import { useState } from 'react';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

interface Props {
  templates: StatusTemplate[];
  selectedId: number | null;
  onSelect: (id: number) => void;
  onCreate: (name: string) => void;
  onSetDefault: (id: number) => void;
  onDelete: (id: number) => void;
  workType: WorkType;
}

export function TemplateSelector({ templates, selectedId, onSelect, onCreate, onSetDefault, onDelete }: Props) {
  const accessCommand = useAccessCommand();
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm();

  const selected = templates.find((t) => t.id === selectedId);

  const handleCreate = () => {
    form.validateFields().then(({ name }) => {
      onCreate(name);
      setCreateOpen(false);
      form.resetFields();
    });
  };

  return (
    <div style={{ padding: '12px 0', display: 'flex', alignItems: 'center', gap: 12 }}>
      <span style={{ fontSize: 12, color: '#999' }}>当前模版:</span>
      <Select
        value={selectedId}
        onChange={onSelect}
        style={{ minWidth: 200 }}
        options={templates.map((t) => ({
          value: t.id,
          label: (
            <Space>
              <span>{t.name}</span>
              {t.isDefault && <Tag color="blue" style={{ fontSize: 10 }}>默认</Tag>}
            </Space>
          ),
        }))}
      />
      {selected && !selected.isDefault && (
        <Button size="small" icon={<StarOutlined />} onClick={() => onSetDefault(selected.id)}>设为默认</Button>
      )}
      {selected && !selected.isDefault && (
        <Popconfirm title="确认删除该模版？" onConfirm={() => onDelete(selected.id)}>
          <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
        </Popconfirm>
      )}
      <Button size="small" type="dashed" icon={<PlusOutlined />}
        onClick={() => accessCommand('READ_WRITE', '新建状态模版', () => setCreateOpen(true))}>
        新建模版
      </Button>

      <Modal title="新建状态模版" open={createOpen} onOk={handleCreate} onCancel={() => setCreateOpen(false)} destroyOnHidden>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="模版名称" rules={[{ required: true, message: '请输入模版名称' }]}>
            <Input placeholder="如: 自定义需求流程" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
