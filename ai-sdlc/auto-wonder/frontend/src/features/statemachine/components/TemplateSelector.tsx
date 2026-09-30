import { Select, Button, Tag, Modal, Form, Input, Popconfirm } from 'antd';
import { StarOutlined, DeleteOutlined } from '@ant-design/icons';
import type { StatusTemplate, WorkType } from '../types';

interface Props {
  createOpen: boolean;
  onCloseCreate: () => void;
  templates: StatusTemplate[];
  selectedId: number | null;
  onSelect: (id: number) => void;
  onCreate: (name: string) => void;
  onSetDefault: (id: number) => void;
  onDelete: (id: number) => void;
  workType: WorkType;
}

export function TemplateSelector({ createOpen, onCloseCreate, templates, selectedId, onSelect, onCreate, onSetDefault, onDelete }: Props) {
  const [form] = Form.useForm();

  const selected = templates.find((t) => t.id === selectedId);

  const handleCreate = () => {
    form.validateFields().then(({ name }) => {
      onCreate(name);
      onCloseCreate();
      form.resetFields();
    });
  };

  return (
    <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
      <span style={{ fontSize: 14, color: 'var(--aw-muted)' }}>当前模版:</span>
      <Select
        value={selectedId}
        onChange={onSelect}
        style={{ minWidth: 200 }}
        options={templates.map((t) => ({
          value: t.id,
          label: (
            <span style={{ display: 'flex', alignItems: 'center', gap: 8, height: '100%' }}>
              <span>{t.name}</span>
              {t.isDefault && <Tag color="blue" style={{ fontSize: 10, lineHeight: '18px' }}>默认</Tag>}
            </span>
          ),
        }))}
      />
      {selected && !selected.isDefault && (
        <Button icon={<StarOutlined />} onClick={() => onSetDefault(selected.id)}>设为默认</Button>
      )}
      {selected && !selected.isDefault && (
        <Popconfirm title="确认删除该模版？" onConfirm={() => onDelete(selected.id)}>
          <Button danger icon={<DeleteOutlined />}>删除</Button>
        </Popconfirm>
      )}

      <Modal title="新建状态模版" open={createOpen} onOk={handleCreate} onCancel={() => onCloseCreate()} destroyOnHidden>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="模版名称" rules={[{ required: true, message: '请输入模版名称' }]}>
            <Input placeholder="如: 自定义需求流程" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
