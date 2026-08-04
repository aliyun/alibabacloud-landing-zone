import { Modal, Form, Select, Input } from 'antd';
import type { StatusNode, StatusTransition } from '../types';

interface Props {
  open: boolean;
  editing: StatusTransition | null;
  nodes: StatusNode[];
  onSubmit: (values: { fromNodeId: number; toNodeId: number; name: string }) => void;
  onCancel: () => void;
  loading?: boolean;
}

export function TransitionFormModal({ open, editing, nodes, onSubmit, onCancel, loading }: Props) {
  const [form] = Form.useForm();

  const nodeOptions = nodes.map((n) => ({ value: n.id, label: `${n.name} (${n.code})` }));

  const handleOpen = () => {
    if (editing) {
      form.setFieldsValue({ fromNodeId: editing.fromNodeId, toNodeId: editing.toNodeId, name: editing.name });
    } else {
      form.resetFields();
    }
  };

  return (
    <Modal
      title={editing ? '编辑推荐流转' : '添加推荐流转'}
      open={open}
      onOk={() => form.validateFields().then(onSubmit)}
      onCancel={onCancel}
      confirmLoading={loading}
      afterOpenChange={(visible) => { if (visible) handleOpen(); }}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item name="fromNodeId" label="从状态" rules={[{ required: true, message: '请选择起始状态' }]}>
          <Select options={nodeOptions} placeholder="选择起始状态" />
        </Form.Item>
        <Form.Item name="toNodeId" label="到状态" rules={[{ required: true, message: '请选择目标状态' }]}>
          <Select options={nodeOptions} placeholder="选择目标状态" />
        </Form.Item>
        <Form.Item name="name" label="操作名称" rules={[{ required: true, message: '请输入操作名称' }]}
          tooltip="将显示为工单页面的快捷按钮文字">
          <Input placeholder="如: 开始开发" />
        </Form.Item>
      </Form>
    </Modal>
  );
}
