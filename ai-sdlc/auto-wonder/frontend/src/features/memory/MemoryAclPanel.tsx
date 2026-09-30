import { Alert, Button, Form, Input, List, Modal, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import { useState } from 'react';
import type { MemoryAcl } from './api';
import { useMemoryAclMutation, useMemoryAcls } from './hooks';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
const subjectLabels = { USER: '平台用户', AGENT: '数字人', SQUAD: '小队成员', ROLE: '全部数字人' } as const;
const permissionLabels = { READ: '查看', WRITE: '编辑', ADMIN: '管理' } as const;
export function MemoryAclPanel({ storeId, administrator }: { storeId?: number; administrator: boolean }) {
  const { data = [] } = useMemoryAcls(storeId, administrator);
  const { put, remove } = useMemoryAclMutation(storeId);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<MemoryAcl>();
  const runWithAccess = useAccessCommand();
  if (!administrator) return <Alert type="info" showIcon message="仅工作空间管理员可维护访问权限" />;
  const submit = async () => {
    await runWithAccess('ADMIN', '设置记忆访问权限', async () => {
      try {
        await put.mutateAsync(await form.validateFields());
        form.resetFields();
        setOpen(false);
      } catch {
        // 校验失败由表单提示；提交失败保持弹窗打开，由 react-query 记录错误状态。
      }
    });
  };
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="访问权限决定谁能查看或编辑这份记忆。" description="组织共享默认供本空间数字人读取，小队共享默认供所属成员读取。数字人仅能自主编辑自己的个人记忆，不能编辑共享记忆，也不能访问其他数字人的个人记忆；平台用户的编辑权限可单独授予。" />
    <Button type="primary" onClick={() => setOpen(true)}>添加访问权限</Button>
    <List dataSource={data} locale={{ emptyText: '暂无额外授权；空间管理员仍可管理，数字人仍可维护自己的个人记忆' }} renderItem={(acl) => <List.Item actions={acl.id ? [<Popconfirm key="delete" title="移除此访问授权？" onConfirm={() => runWithAccess('ADMIN', '移除记忆访问权限', () => remove.mutate(acl.id!))}><Button danger type="link">删除</Button></Popconfirm>] : []}><Typography.Text>{subjectLabels[acl.subjectType]} · {acl.subjectRef}</Typography.Text><Tag>{permissionLabels[acl.permission]}</Tag></List.Item>} />
    <Modal title="新增记忆权限" open={open} onCancel={() => setOpen(false)} onOk={submit} confirmLoading={put.isPending}>
      <Form form={form} layout="vertical" initialValues={{ subjectType: 'AGENT', permission: 'READ' }}>
        <Form.Item name="subjectType" label="授权对象" rules={[{ required: true }]}><Select options={Object.entries(subjectLabels).map(([value, label]) => ({ value, label }))} /></Form.Item>
        <Form.Item name="subjectRef" label="对象编号" extra="填写用户、数字人或小队的 ID，例如数字人 40013；选择全部数字人时填 ALL_AGENTS。" rules={[{ required: true }]}><Input placeholder="例如 40013" /></Form.Item>
        <Form.Item name="permission" label="允许操作" extra="查看：读取内容；编辑：新增、修改、删除；管理：包含编辑权限。数字人实际写入仍仅限其个人记忆。" rules={[{ required: true }]}><Select options={Object.entries(permissionLabels).map(([value, label]) => ({ value, label }))} /></Form.Item>
      </Form>
    </Modal>
  </Space>;
}
