import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Divider, Form, Input, Modal, Space, Typography } from 'antd';
import { CloseCircleFilled, EyeOutlined, EyeInvisibleOutlined, LoadingOutlined, UserOutlined, LockOutlined } from '@ant-design/icons';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { login } from './api';
import {
  SSO_LOGIN_CONFIG_QUERY_KEY,
  SSO_PROVIDERS_QUERY_KEY,
  beginSsoLogin,
  fetchSsoLoginConfig,
  fetchSsoProviders,
  ssoStartUrl,
} from './ssoApi';
import { useAuthStore } from '@/shared/auth/store';
import { currentReturnTarget } from '@/shared/auth/returnPath';
import { resolvePostLoginNavigation } from './postLoginNavigation';
import { ApiError, ErrorCodes } from '@/shared/types/common';
import { readAdministratorContacts, type AdministratorContact } from '@/shared/types/administrator';
import { AdministratorContactList } from '@/shared/ui/AdministratorContactList';
import { AuthEntryShell } from './AuthEntryShell';

const { Title, Text } = Typography;

export function LoginPage() {
  const { t } = useTranslation('auth');
  const [form] = Form.useForm();
  useLocalizedValidation(form);
  const [error, setError] = useState<{ message: string } | { key: 'login.failed' } | null>(null);
  const [pendingContacts, setPendingContacts] = useState<AdministratorContact[] | null>(null);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const setTokens = useAuthStore((s) => s.setTokens);
  const setUser = useAuthStore((s) => s.setUser);
  const accessToken = useAuthStore((s) => s.accessToken);
  // 未启用任何连接的部署（或接口失败）保持原样：只展示本地账号登录。
  const providersQuery = useQuery({
    queryKey: SSO_PROVIDERS_QUERY_KEY,
    queryFn: fetchSsoProviders,
    retry: false,
  });
  const configQuery = useQuery({
    queryKey: SSO_LOGIN_CONFIG_QUERY_KEY,
    queryFn: fetchSsoLoginConfig,
    retry: false,
  });
  const providers = providersQuery.data ?? [];
  const registrationEnabled = configQuery.data?.registrationEnabled ?? true;
  const defaultProviderId = configQuery.data?.defaultProviderId ?? null;
  const returnTarget = currentReturnTarget((location.state as { from?: unknown } | null)?.from);
  // 仅 local=1 明确要求本地登录；local=0 或空值仍按默认连接自动发起。
  const localRequested = new URLSearchParams(location.search).get('local') === '1';
  const defaultProvider = defaultProviderId == null
    ? null
    : providers.find((provider) => provider.providerId === defaultProviderId) ?? null;
  const autoStartUrl = !localRequested && !accessToken && providersQuery.isFetched && defaultProvider
    ? ssoStartUrl(defaultProvider.providerId, returnTarget)
    : null;
  const autoStartedRef = useRef(false);

  useEffect(() => {
    if (!autoStartUrl || autoStartedRef.current) return;
    autoStartedRef.current = true;
    beginSsoLogin(autoStartUrl);
  }, [autoStartUrl]);

  const onFinish = async (values: { username: string; password: string }) => {
    setError(null);
    setLoading(true);
    try {
      const resp = await login(values);
      setTokens(resp.accessToken, resp.refreshToken);
      setUser(resp.user);
      // 只恢复站内安全路径；原空间不可用时退回空间选择页。
      navigate(await resolvePostLoginNavigation((location.state as { from?: unknown } | null)?.from), {
        replace: true,
      });
    } catch (e) {
      // 正确凭据但账号待审：弹窗展示审核提示与管理员联系方式；错误凭据保持原错误提示。
      if (e instanceof ApiError && e.code === ErrorCodes.USER_REGISTRATION_PENDING_REVIEW) {
        setPendingContacts(readAdministratorContacts(e.data));
      } else if (e instanceof ApiError) {
        setError({ message: e.message });
      } else {
        setError({ key: 'login.failed' });
      }
    } finally {
      setLoading(false);
    }
  };

  const errorText = error && ('key' in error ? t(error.key) : error.message);
  return (
    <AuthEntryShell>
      <Card className="auth-entry-card" variant="borderless">
        <Title level={3} className="auth-entry-title">{t('login.title')}</Title>
        {autoStartUrl && (
          <Alert
            type="info"
            showIcon
            message={t('sso.redirecting')}
            action={<Link to="/login?local=1">{t('sso.useLocalLogin')}</Link>}
            style={{ marginBottom: 16 }}
          />
        )}
        {providers.length > 0 && (
          <div style={{ marginBottom: 16 }}>
            <div style={{ marginBottom: 12 }}>
              <Text type="secondary">{t('sso.heading')}</Text>
            </div>
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              {providers.map((provider) => (
                <Button
                  key={provider.providerId}
                  block
                  href={ssoStartUrl(provider.providerId, returnTarget)}
                  data-testid={`sso-provider-${provider.providerId}`}
                >
                  {t('sso.loginWith', { name: provider.name })}
                </Button>
              ))}
            </Space>
            <Divider plain>{t('sso.or')}</Divider>
            <Title level={5} style={{ marginBottom: 0 }}>{t('sso.localHeading')}</Title>
          </div>
        )}
        {error && <Alert message={errorText} type="error" showIcon icon={<CloseCircleFilled aria-hidden="true" />} style={{ marginBottom: 16 }} />}
        <Form form={form} onFinish={onFinish} layout="vertical" noValidate>
          <Form.Item label={t('fields.username')} name="username" rules={[{ required: true, message: t('fields.usernameRequired') }]}>
            <Input autoComplete="username" prefix={<UserOutlined aria-hidden="true" />} placeholder={t('fields.username')} />
          </Form.Item>
          <Form.Item label={t('fields.password')} name="password" rules={[{ required: true, message: t('fields.passwordRequired') }]}>
            <Input.Password iconRender={visible => <Button type="text" htmlType="button" aria-label={t(visible ? 'fields.hidePassword' : 'fields.showPassword')}
              icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />} style={{ padding: 0, width: 16, height: 16 }} />}
              autoComplete="current-password" prefix={<LockOutlined aria-hidden="true" />} placeholder={t('fields.password')} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" aria-label={t('login.title')} loading={loading ? { icon: <LoadingOutlined aria-hidden="true" /> } : false} block>{t('login.title')}</Button>
          </Form.Item>
          {registrationEnabled && (
            <div className="auth-entry-link">
              <Link to="/register">{t('login.registerLink')}</Link>
            </div>
          )}
        </Form>
      </Card>
      <Modal
        open={pendingContacts !== null}
        title={t('login.pendingTitle')}
        closable={false}
        maskClosable={false}
        onOk={() => setPendingContacts(null)}
        okText={t('login.pendingOk')}
        cancelButtonProps={{ style: { display: 'none' } }}
      >
        <p>{t('login.pendingHint')}</p>
        <AdministratorContactList
          contacts={pendingContacts ?? []}
          title={t('contacts.title')}
          emptyText={t('contacts.empty')}
        />
      </Modal>
    </AuthEntryShell>
  );
}
