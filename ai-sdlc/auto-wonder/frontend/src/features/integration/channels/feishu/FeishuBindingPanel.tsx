import { useState } from 'react';
import { Alert, Button, Checkbox, Drawer, Form, Input, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listAgents } from '@/features/agent/api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { deleteFeishuBinding, listFeishuBindings, saveFeishuBinding, type FeishuBinding, type FeishuBindingRequest } from './feishuApi';

export function FeishuBindingPanel() {
  const runWithAccess = useAccessCommand();
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [record, setRecord] = useState<FeishuBinding>();
  const [form] = Form.useForm<FeishuBindingRequest>();
  const bindings = useQuery({ queryKey: ['feishu-bindings'], queryFn: listFeishuBindings, refetchInterval: 15000 });
  const agents = useQuery({ queryKey: ['agents-for-binding'], queryFn: () => listAgents({ page: 1, size: 200 }) });
  const refresh = () => client.invalidateQueries({ queryKey: ['feishu-bindings'] });
  const save = useMutation({
    mutationFn: (values: FeishuBindingRequest) => saveFeishuBinding(record?.id, { ...values, version: record?.version }),
    onSuccess: (saved) => {
      setRecord(saved);
      form.resetFields();
      form.setFieldsValue({ appId: saved.appId, agentId: saved.agentId, status: saved.status });
      refresh();
      message.success('飞书绑定已保存，请配置回调地址并发布飞书应用');
    },
    onError: (error: Error) => message.error(error.message || '保存失败'),
  });
  const remove = useMutation({ mutationFn: deleteFeishuBinding, onSuccess: refresh, onError: (error: Error) => message.error(error.message) });
  function edit(value?: FeishuBinding) {
    runWithAccess('ADMIN', value ? '编辑飞书绑定' : '新建飞书绑定', () => {
      setRecord(value); form.resetFields();
      form.setFieldsValue({ appId: value?.appId, agentId: value?.agentId, status: value?.status ?? 'ENABLED' });
      setOpen(true);
    });
  }
  function submit() {
    runWithAccess('ADMIN', '保存飞书绑定', async () => {
      try { const values = await form.validateFields(); save.mutate(values); } catch { /* Form shows validation errors. */ }
    });
  }
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Alert type="info" showIcon message="飞书企业自建应用机器人"
      description="通过 HTTP 回调接收消息。支持单聊文本和群内 @机器人多轮对话，回复以文本发送。请先在飞书开放平台启用机器人，并为数字人配置在线执行器。" />
    <Button type="primary" onClick={() => edit()}>新建飞书绑定</Button>
    {bindings.isError && <Alert type="error" message="飞书绑定加载失败" description={(bindings.error as Error).message} action={<Button onClick={() => bindings.refetch()}>重试</Button>} />}
    <Table<FeishuBinding> rowKey="id" dataSource={bindings.data ?? []} loading={bindings.isLoading}
      locale={{ emptyText: '暂无飞书绑定' }} scroll={{ x: 900 }} columns={[
        { title: 'App ID', dataIndex: 'appId' },
        { title: '数字人', dataIndex: 'agentId', render: (id: number) => agents.data?.find(a => a.id === id)?.name ?? `#${id}` },
        { title: '状态', dataIndex: 'status', render: (status: string) => <Tag color={status === 'ENABLED' ? 'green' : 'default'}>{status === 'ENABLED' ? '已启用' : '已停用'}</Tag> },
        { title: '最近成功处理', dataIndex: 'lastSuccessAt', render: (date: string | null) => date ? new Date(date).toLocaleString() : '尚无记录' },
        { title: '最近异常', dataIndex: 'lastError', render: (error: string | null) => error || '—' },
        { title: '操作', render: (_, row) => <Space>
          <Button type="link" onClick={() => edit(row)}>编辑</Button>
          <Popconfirm title="删除此飞书绑定？" description="删除后将停止接收和回复该应用消息。" onConfirm={() => runWithAccess('ADMIN', '删除飞书绑定', () => remove.mutate(row.id))}>
            <Button type="link" danger loading={remove.isPending}>删除</Button>
          </Popconfirm>
        </Space> },
      ]} />
    <Drawer title={record ? '编辑飞书绑定' : '新建飞书绑定'} open={open} onClose={() => setOpen(false)} width={560}
      extra={<Button type="primary" loading={save.isPending} onClick={submit}>保存</Button>}>
      <Form form={form} layout="vertical">
        <Form.Item name="appId" label="App ID" rules={[{ required: true, pattern: /^cli_[A-Za-z0-9]+$/, message: '请输入有效的 App ID（cli_ 开头）' }]}>
          <Input disabled={!!record} placeholder="cli_xxxxxxxxx" maxLength={128} />
        </Form.Item>
        <Form.Item name="appSecret" label="App Secret" extra={record ? '留空保留已有密钥' : undefined} rules={[{ required: !record, whitespace: true, message: '请输入 App Secret' }]}>
          <Input.Password autoComplete="new-password" maxLength={512} />
        </Form.Item>
        <Form.Item name="verificationToken" label="Verification Token" extra={record ? '留空保留已有 Token' : '在飞书应用的「事件与回调 → 加密策略」中获取'} rules={[{ required: !record, whitespace: true, message: '请输入 Verification Token' }]}>
          <Input.Password autoComplete="new-password" maxLength={512} />
        </Form.Item>
        <Form.Item name="encryptKey" label="Encrypt Key" extra={record?.encryptKeyConfigured ? '已配置加密；留空保留已有密钥' : '可选，与飞书加密策略保持一致；配置后验证签名并解密事件'}>
          <Input.Password autoComplete="new-password" maxLength={512} />
        </Form.Item>
        {record?.encryptKeyConfigured && <Form.Item name="clearEncryptKey" valuePropName="checked"><Checkbox>清除 Encrypt Key（需同时关闭飞书端加密）</Checkbox></Form.Item>}
        <Form.Item name="agentId" label="关联数字人" rules={[{ required: true, message: '请选择数字人' }]}>
          <Select showSearch optionFilterProp="label" loading={agents.isLoading} options={(agents.data ?? []).map(a => ({ label: a.name, value: a.id }))} />
        </Form.Item>
        {agents.isError && <Alert type="error" message="数字人列表加载失败" />}
        <Form.Item name="status" label="绑定状态"><Select options={[{ label: '启用', value: 'ENABLED' }, { label: '停用', value: 'DISABLED' }]} /></Form.Item>
      </Form>
      {record && <Alert type="success" message="回调地址（复制到飞书事件订阅）" description={<Typography.Paragraph copyable style={{ wordBreak: 'break-all', marginBottom: 0 }}>
        {record.callbackUrl.startsWith('/') ? `${window.location.origin}${record.callbackUrl}` : record.callbackUrl}
      </Typography.Paragraph>} />}
      <Typography.Paragraph style={{ marginTop: 16 }}>
        保存后，在飞书配置「将事件发送至开发者服务器」，填入公网 HTTPS 回调地址，订阅「接收消息 im.message.receive_v1」。
        开通「获取用户发给机器人的单聊消息」「接收群聊中 @机器人消息」「以应用的身份发消息」权限，发布应用并将机器人加入群聊。
      </Typography.Paragraph>
      <Typography.Link href="https://open.feishu.cn/document/server-docs/im-v1/message/events/receive" target="_blank" rel="noreferrer">飞书接收消息文档</Typography.Link>
    </Drawer>
  </Space>;
}
