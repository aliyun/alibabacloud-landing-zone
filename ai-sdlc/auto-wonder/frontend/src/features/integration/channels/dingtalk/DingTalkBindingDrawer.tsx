import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Collapse,
  Drawer,
  Form,
  Input,
  Select,
  Space,
  Typography,
  message,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listAgents } from '@/features/agent/api';
import {
  createDingTalkBinding,
  DINGTALK_DEFAULT_BASE_URL,
  updateDingTalkBinding,
  type DingTalkBinding,
  type DingTalkBindingRequest,
  type TransportMode,
} from './dingtalkApi';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { Paragraph, Text } = Typography;

interface DrawerFormValues {
  agentId: number;
  appKey: string;
  appSecret?: string;
  robotCode: string;
  transportMode?: TransportMode;
  baseUrl?: string;
  regionId?: string;
  callbackToken?: string;
}

interface Props {
  open: boolean;
  mode: 'create' | 'edit';
  record: DingTalkBinding | null;
  onClose: () => void;
  onSaved: () => void;
}

export function DingTalkBindingDrawer({ open, mode, record, onClose, onSaved }: Props) {
  const [form] = Form.useForm<DrawerFormValues>();
  const queryClient = useQueryClient();
  const runAccessCommand = useAccessCommand();
  const [callbackUrl, setCallbackUrl] = useState<string | null>(null);

  const { data: agents = [] } = useQuery({
    queryKey: ['agents-for-binding'],
    queryFn: () => listAgents({ page: 1, size: 200 }),
  });

  useEffect(() => {
    if (!open) return;
    setCallbackUrl(null);
    if (mode === 'edit' && record) {
      form.setFieldsValue({
        agentId: record.agentId,
        appKey: record.appKey,
        appSecret: undefined,
        robotCode: record.robotCode,
        transportMode: record.transportMode,
        baseUrl: record.baseUrl ?? undefined,
        regionId: record.regionId ?? undefined,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ transportMode: 'STREAM' });
    }
  }, [open, mode, record, form]);

  const saveMutation = useMutation({
    mutationFn: async (values: DrawerFormValues) => {
      const body: DingTalkBindingRequest = {
        appKey: values.appKey,
        robotCode: values.robotCode,
        agentId: values.agentId,
        transportMode: values.transportMode ?? record?.transportMode ?? 'STREAM',
        streamEnv: 'ONLINE',
        baseUrl: values.baseUrl,
        regionId: values.regionId,
        callbackToken: values.callbackToken,
      };
      if (values.appSecret) {
        body.appSecret = values.appSecret;
      }
      if (mode === 'edit' && record) {
        return updateDingTalkBinding(record.id, body);
      }
      return createDingTalkBinding(body);
    },
    onSuccess: (binding) => {
      message.success('绑定已保存');
      queryClient.invalidateQueries({ queryKey: ['dingtalk-bindings'] });
      setCallbackUrl(binding.callbackUrl);
      onSaved();
    },
    onError: (error: Error) => message.error(error.message || '保存失败'),
  });

  async function handleSubmit() {
    runAccessCommand('ADMIN', mode === 'edit' ? '保存钉钉绑定修改' : '保存钉钉绑定', async () => {
      let values: DrawerFormValues;
      try {
        values = await form.validateFields();
      } catch {
        return;
      }
      saveMutation.mutate(values);
    });
  }

  const agentOptions = agents.map((agent) => ({
    label: agent.roleName ? `${agent.name}（${agent.roleName}）` : agent.name,
    value: agent.id,
  }));

  return (
    <Drawer
      title={mode === 'edit' ? '编辑绑定' : '新建绑定'}
      width={480}
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saveMutation.isPending} onClick={handleSubmit}>
            保存
          </Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical">
        <Form.Item
          label="关联数字人"
          name="agentId"
          rules={[{ required: true, message: '请选择关联数字人' }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="选择本租户数字人"
            options={agentOptions}
          />
        </Form.Item>
        <Form.Item label="appKey" name="appKey" rules={[{ required: true, message: '请填写 appKey' }]}>
          <Input placeholder="dingxxxxxx" />
        </Form.Item>
        <Form.Item
          label={mode === 'edit' ? 'appSecret（留空=不修改）' : 'appSecret'}
          name="appSecret"
          rules={mode === 'create' ? [{ required: true, message: '请填写 appSecret' }] : []}
        >
          <Input.Password placeholder={mode === 'edit' ? '••••••••' : '应用密钥'} />
        </Form.Item>
        <Form.Item
          label="robotCode"
          name="robotCode"
          rules={[{ required: true, message: '请填写 robotCode' }]}
        >
          <Input placeholder="robot_xxxxxx" />
        </Form.Item>

        <Collapse
          ghost
          items={[
            {
              key: 'advanced',
              label: '高级设置（传输方式 / 网关地址 / regionId）',
              children: (
                <>
                  <Form.Item label="传输方式" name="transportMode">
                    <Select
                      options={[
                        { label: 'HTTP 回调', value: 'HTTP_CALLBACK' },
                        { label: 'Stream', value: 'STREAM' },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item label="网关地址 baseUrl" name="baseUrl">
                    <Input placeholder={DINGTALK_DEFAULT_BASE_URL} />
                  </Form.Item>
                  <Form.Item label="regionId" name="regionId">
                    <Input placeholder="留空使用默认" />
                  </Form.Item>
                  <Form.Item label="回调 token callbackToken" name="callbackToken">
                    <Input placeholder="留空自动生成" />
                  </Form.Item>
                </>
              ),
            },
          ]}
        />
      </Form>

      {callbackUrl && (
        <Alert
          style={{ marginTop: 16 }}
          type="success"
          showIcon
          message="回调地址（复制到钉钉机器人后台）"
          description={
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Paragraph copyable={{ text: callbackUrl }} style={{ marginBottom: 0 }}>
                <Text code>{callbackUrl}</Text>
              </Paragraph>
            </Space>
          }
        />
      )}
    </Drawer>
  );
}
