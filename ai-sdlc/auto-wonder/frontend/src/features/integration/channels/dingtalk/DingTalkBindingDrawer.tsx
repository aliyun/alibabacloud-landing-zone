import { EyeOutlined, EyeInvisibleOutlined, CopyOutlined, CheckOutlined } from '@ant-design/icons';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { getAgentRoleLabel } from '@/features/agent/constants';
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
  const { t } = useTranslation(['integration', 'agent']);
  const [form] = Form.useForm<DrawerFormValues>();
  useLocalizedValidation(form, open);
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
      message.success(i18n.t('integration:bindingSaved'));
      queryClient.invalidateQueries({ queryKey: ['dingtalk-bindings'] });
      setCallbackUrl(binding.callbackUrl);
      onSaved();
    },
    onError: (error: Error) => message.error(error.message || i18n.t('integration:saveFailed')),
  });

  async function handleSubmit() {
    runAccessCommand('ADMIN', mode === 'edit' ? t('saveDingEdit') : t('saveDing'), async () => {
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
    label: getAgentRoleLabel(agent.roleCode, agent.roleName) ? t('display.bindingLabel', { ns: 'agent', name: agent.name, label: getAgentRoleLabel(agent.roleCode, agent.roleName) }) : agent.name,
    value: agent.id,
  }));

  return (
    <Drawer closable={{ 'aria-label': t('close') }}
      title={mode === 'edit' ? t('editBinding') : t('newBinding')}
      width={480}
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={
        <Space>
          <Button onClick={onClose}>{t('cancel')}</Button>
          <Button type="primary" aria-label={t('save')} loading={saveMutation.isPending} onClick={handleSubmit}>{t('save')}</Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical">
        <Form.Item
          label={t('linkedAgent')}
          name="agentId"
          rules={[{ required: true, message: t('requiredAgent') }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            placeholder={t('agentPlaceholder')}
            options={agentOptions}
          />
        </Form.Item>
        <Form.Item label={t('appKey')} name="appKey" rules={[{ required: true, message: t('requiredAppKey') }]}>
          <Input placeholder="dingxxxxxx" />
        </Form.Item>
        <Form.Item
          label={mode === 'edit' ? t('appSecretEdit') : t('appSecret')}
          name="appSecret"
          rules={mode === 'create' ? [{ required: true, message: t('requiredAppSecret') }] : []}
        >
          <Input.Password iconRender={(visible) => <Button type="text" htmlType="button" aria-label={visible ? t('hideSecret') : t('showSecret')}
                icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />}
                style={{ padding: 0, width: 16, height: 16 }} />} placeholder={mode === 'edit' ? '••••••••' : t('secretPlaceholder')} />
        </Form.Item>
        <Form.Item
          label={t('robotCode')}
          name="robotCode"
          rules={[{ required: true, message: t('requiredRobotCode') }]}
        >
          <Input placeholder="robot_xxxxxx" />
        </Form.Item>

        <Collapse
          ghost
          items={[
            {
              key: 'advanced',
              label: t('advanced'),
              children: (
                <>
                  <Form.Item label={t('transportMode')} name="transportMode">
                    <Select
                      options={[
                        { label: t('httpCallback'), value: 'HTTP_CALLBACK' },
                        { label: t('stream'), value: 'STREAM' },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item label={t('gateway')} name="baseUrl">
                    <Input placeholder={DINGTALK_DEFAULT_BASE_URL} />
                  </Form.Item>
                  <Form.Item label={t('dingRegion')} name="regionId">
                    <Input placeholder={t('defaultPlaceholder')} />
                  </Form.Item>
                  <Form.Item label={t('callbackToken')} name="callbackToken">
                    <Input placeholder={t('generatedPlaceholder')} />
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
          message={t('dingCallback')}
          description={
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              <Paragraph copyable={{ text: callbackUrl, tooltips: [t('copyCallback'), t('copied')], icon: [<CopyOutlined aria-hidden="true" />, <CheckOutlined aria-hidden="true" />] }} style={{ marginBottom: 0 }}>
                <Text code>{callbackUrl}</Text>
              </Paragraph>
            </Space>
          }
        />
      )}
    </Drawer>
  );
}
