import { useEffect } from 'react';
import { Form, Modal, Radio, Select } from 'antd';
import type { WorkspaceAccessLevel } from '@/shared/types/common';
import type { MemberVO } from './api';

interface MemberAccessForm {
  accessLevel: WorkspaceAccessLevel;
  identityTags: string[];
}

interface MemberAccessModalProps {
  open: boolean;
  member: MemberVO | null;
  loading: boolean;
  onClose: () => void;
  onConfirm: (values: MemberAccessForm) => void | Promise<void>;
}

function normalizeTags(tags: string[] | undefined) {
  return Array.from(new Set(
    (tags ?? []).map((tag) => tag.trim()).filter(Boolean),
  ));
}

export function MemberAccessModal({
  open,
  member,
  loading,
  onClose,
  onConfirm,
}: MemberAccessModalProps) {
  const [form] = Form.useForm<MemberAccessForm>();

  useEffect(() => {
    if (open && member) {
      form.setFieldsValue({
        accessLevel: member.accessLevel,
        identityTags: member.identityTags,
      });
    } else {
      form.resetFields();
    }
  }, [form, member, open]);

  const handleOk = async () => {
    let values: MemberAccessForm;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    await onConfirm({
      accessLevel: values.accessLevel,
      identityTags: normalizeTags(values.identityTags),
    });
  };

  return (
    <Modal
      title="编辑成员"
      open={open}
      okText="保存"
      cancelText="取消"
      confirmLoading={loading}
      onCancel={onClose}
      onOk={handleOk}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item
          name="accessLevel"
          label="访问等级"
          rules={[{ required: true, message: '请选择访问等级' }]}
        >
          <Radio.Group>
            <Radio value="READ_ONLY">只读权限</Radio>
            <Radio value="READ_WRITE">读写权限</Radio>
            <Radio value="ADMIN">管理员权限</Radio>
          </Radio.Group>
        </Form.Item>
        <Form.Item
          name="identityTags"
          label="身份标签"
          rules={[{
            validator: (_, value: string[] | undefined) => {
              const tags = normalizeTags(value);
              if (tags.length > 8) {
                return Promise.reject(new Error('身份标签最多 8 项'));
              }
              if (tags.some((tag) => tag.length > 32)) {
                return Promise.reject(new Error('每项身份标签最多 32 个字符'));
              }
              return Promise.resolve();
            },
          }]}
        >
          <Select
            mode="tags"
            aria-label="身份标签"
            placeholder="输入标签后按回车"
            tokenSeparators={[',']}
            options={(member?.identityTags ?? []).map((tag) => ({ label: tag, value: tag }))}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
