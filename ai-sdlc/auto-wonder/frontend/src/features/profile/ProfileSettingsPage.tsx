import { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Alert, Button, Card, Form, Input, Space, Tabs, Tag, Tooltip, Typography, message } from 'antd';
import { SaveOutlined, SendOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { McpTokenSettingsPanel } from '@/features/open-platform/McpTokenSettingsPanel';
import { ChangePasswordPanel } from './ChangePasswordPanel';
import {
  USER_IM_IDENTITIES_QUERY_KEY,
  listMyImIdentities,
  sendMyDingTalkIdentityTest,
  updateMyDingTalkIdentity,
  type UserImIdentity,
} from './profileApi';

const { Title, Text } = Typography;

const DEFAULT_TAB = 'im';

type DingTalkFormValues = {
  externalUserId: string;
};

const EMPTY_DINGTALK_IDENTITY: UserImIdentity = {
  provider: 'DINGTALK',
  externalUserId: '',
  configured: false,
  platformReady: true,
  testAvailable: false,
};

function getApiMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function getDingTalkTestStatusText(hasIdentityRow: boolean, hasSavedIdentity: boolean, identity: UserImIdentity) {
  if (!hasIdentityRow || !hasSavedIdentity) {
    return '请先保存工号';
  }
  if (!identity.platformReady) {
    return '统一机器人未配置';
  }
  return identity.testAvailable ? '可测试' : '请先保存工号';
}

export function ProfileSettingsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedTab = searchParams.get('tab');
  const activeTab = requestedTab === 'mcp' || requestedTab === 'password' ? requestedTab : DEFAULT_TAB;
  const [form] = Form.useForm<DingTalkFormValues>();
  const queryClient = useQueryClient();
  const hydratedRef = useRef(false);
  const identityQuery = useQuery({
    queryKey: USER_IM_IDENTITIES_QUERY_KEY,
    queryFn: listMyImIdentities,
    retry: false,
  });

  const dingTalkIdentityRow = identityQuery.data?.find((item) => item.provider?.toUpperCase() === 'DINGTALK');
  const hasDingTalkIdentityRow = Boolean(dingTalkIdentityRow);
  const dingTalkIdentity = dingTalkIdentityRow || EMPTY_DINGTALK_IDENTITY;
  const savedExternalUserId = dingTalkIdentity.externalUserId || '';
  const currentExternalUserId = Form.useWatch('externalUserId', form) ?? savedExternalUserId;
  const normalizedCurrentExternalUserId = currentExternalUserId.trim();
  const normalizedSavedExternalUserId = savedExternalUserId.trim();
  const hasSavedIdentity = Boolean(dingTalkIdentity.configured && normalizedSavedExternalUserId);
  const testBlockedByRobot = !identityQuery.isLoading && hasDingTalkIdentityRow && !dingTalkIdentity.platformReady;
  const testBlockedByIdentity =
    !normalizedCurrentExternalUserId ||
    !hasSavedIdentity ||
    normalizedCurrentExternalUserId !== normalizedSavedExternalUserId;

  useEffect(() => {
    if (identityQuery.data) {
      const fieldTouched = form.isFieldTouched('externalUserId');
      if (!hydratedRef.current || !fieldTouched) {
        form.setFieldsValue({ externalUserId: savedExternalUserId });
        hydratedRef.current = true;
      }
    }
  }, [form, identityQuery.data, savedExternalUserId]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      const values = await form.validateFields();
      return updateMyDingTalkIdentity({ externalUserId: values.externalUserId?.trim() || '' });
    },
    onSuccess: async () => {
      message.success('IM 工号已保存');
      await queryClient.invalidateQueries({ queryKey: USER_IM_IDENTITIES_QUERY_KEY });
    },
    onError: (error) => {
      message.error(getApiMessage(error, 'IM 工号保存失败'));
    },
  });

  const testMutation = useMutation({
    mutationFn: sendMyDingTalkIdentityTest,
    onSuccess: () => {
      message.success('测试消息已发送');
    },
    onError: (error) => {
      message.error(getApiMessage(error, '测试消息发送失败'));
    },
  });

  const testDisabled =
    identityQuery.isLoading ||
    saveMutation.isPending ||
    testMutation.isPending ||
    testBlockedByRobot ||
    testBlockedByIdentity;
  const testDisabledReason = testBlockedByRobot
    ? '系统统一钉钉机器人未配置'
    : testBlockedByIdentity
      ? '请先保存钉钉工号后再测试'
      : '';
  const testStatusText = getDingTalkTestStatusText(hasDingTalkIdentityRow, hasSavedIdentity, dingTalkIdentity);

  const imSettings = (
    <Card title="IM 工号" styles={{ body: { padding: 18 } }}>
        {identityQuery.error ? (
          <Alert
            type="error"
            showIcon
            message="IM 工号加载失败"
            description={getApiMessage(identityQuery.error, '请稍后重试')}
          />
        ) : (
          <Form
            form={form}
            layout="vertical"
            disabled={identityQuery.isLoading || saveMutation.isPending}
            initialValues={{ externalUserId: '' }}
            onFinish={() => saveMutation.mutate()}
          >
            <div style={{ display: 'grid', gap: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
                <div>
                  <Text strong>钉钉</Text>
                  <div>
                    <Text type="secondary" style={{ fontSize: 12 }}>保存钉钉工号后可接收指派和测试通知。</Text>
                  </div>
                </div>
                <Space size={[8, 8]} wrap>
                  <Tag color={hasSavedIdentity ? 'green' : 'default'}>{hasSavedIdentity ? '已开通' : '未开通'}</Tag>
                  <Tag color={dingTalkIdentity.testAvailable ? 'green' : 'orange'}>
                    {testStatusText}
                  </Tag>
                </Space>
              </div>

              <Form.Item
                label="钉钉工号"
                name="externalUserId"
                extra="保存非空工号即开通；清空后保存会关闭钉钉身份。"
              >
                <Input maxLength={256} placeholder="例如 staff-001" autoComplete="off" />
              </Form.Item>

              {testDisabledReason ? (
                <Alert type="info" showIcon message={testDisabledReason} />
              ) : null}

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, flexWrap: 'wrap' }}>
                <Tooltip title={testDisabledReason || undefined}>
                  <Button
                    icon={<SendOutlined />}
                    disabled={testDisabled}
                    loading={testMutation.isPending}
                    onClick={() => testMutation.mutate()}
                  >
                    发送测试
                  </Button>
                </Tooltip>
                <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saveMutation.isPending}>
                  保存
                </Button>
              </div>
            </div>
          </Form>
        )}
    </Card>
  );

  return (
    <div data-testid="profile-settings-shell" style={{ maxWidth: activeTab === 'mcp' ? 1440 : 1100, margin: '0 auto' }}>
      <div style={{ marginBottom: 18 }}>
        <Title level={3} style={{ margin: 0, letterSpacing: 0 }}>个人设置</Title>
        <Text type="secondary">管理只属于当前账号的全局个人配置。</Text>
      </div>

      <Tabs
        activeKey={activeTab}
        onChange={(key) => {
          const next = new URLSearchParams(searchParams);
          if (key === DEFAULT_TAB) {
            next.delete('tab');
          } else {
            next.set('tab', key);
          }
          setSearchParams(next, { replace: true });
        }}
        items={[
          { key: 'im', label: 'IM 工号', children: imSettings },
          { key: 'mcp', label: 'MCP 令牌', children: <McpTokenSettingsPanel /> },
          { key: 'password', label: '修改密码', children: <ChangePasswordPanel /> },
        ]}
      />
    </div>
  );
}
