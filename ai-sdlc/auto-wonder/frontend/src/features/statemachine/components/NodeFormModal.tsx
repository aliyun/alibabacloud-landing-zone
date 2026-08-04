import { Modal, Form, Input, Select, InputNumber } from 'antd';
import type { StatusNode, NodeCategory } from '../types';

const categoryOptions: { value: NodeCategory; label: string }[] = [
  { value: 'INIT', label: '初始态' },
  { value: 'IN_PROGRESS', label: '进行中' },
  { value: 'DONE', label: '完成态' },
  { value: 'CANCELED', label: '取消态' },
];

interface Props {
  open: boolean;
  editing: StatusNode | null;
  nextSort: number;
  onSubmit: (values: { code: string; name: string; category: string; sort: number }) => void;
  onCancel: () => void;
  loading?: boolean;
}

export function NodeFormModal({ open, editing, nextSort, onSubmit, onCancel, loading }: Props) {
  const [form] = Form.useForm();

  const handleOpen = () => {
    if (editing) {
      form.setFieldsValue({ code: editing.code, name: editing.name, category: editing.category, sort: editing.sort });
    } else {
      form.resetFields();
      form.setFieldsValue({ category: 'IN_PROGRESS', sort: nextSort });
    }
  };

  return (
    <Modal
      title={editing ? '编辑状态节点' : '添加状态节点'}
      open={open}
      onOk={() => form.validateFields().then(onSubmit)}
      onCancel={onCancel}
      confirmLoading={loading}
      afterOpenChange={(visible) => { if (visible) handleOpen(); }}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item name="code" label="编码" rules={[{ required: true, message: '请输入状态编码' }]}
          tooltip="英文标识符，如 developing、verifying">
          <Input placeholder="如: developing" disabled={!!editing} />
        </Form.Item>
        <Form.Item name="name" label="显示名称" rules={[{ required: true, message: '请输入显示名称' }]}>
          <Input placeholder="如: 开发中" />
        </Form.Item>
        <Form.Item name="category" label="分类" rules={[{ required: true, message: '请选择分类' }]}>
          <Select options={categoryOptions} />
        </Form.Item>
        <Form.Item name="sort" label="排序" tooltip="数字越小越靠前">
          <InputNumber min={0} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
