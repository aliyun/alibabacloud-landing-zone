import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { Alert, Button, Card, Divider, Form, Input, Space, Typography } from 'antd';
import { CloseCircleFilled, EyeOutlined, EyeInvisibleOutlined, LoadingOutlined, UserOutlined, LockOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { login } from './api';
import { SSO_PROVIDERS_QUERY_KEY, fetchSsoProviders } from './ssoApi';
import { useAuthStore } from '@/shared/auth/store';
import { ApiError } from '@/shared/types/common';
import { AuthEntryShell } from './AuthEntryShell';

const { Title, Text } = Typography;

export function LoginPage() {
  const { t } = useTranslation('auth');
  const [form] = Form.useForm();
  useLocalizedValidation(form);
  const [error, setError] = useState<{ message: string } | { key: 'login.failed' } | null>(null);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const setTokens = useAuthStore((s) => s.setTokens);
  const setUser = useAuthStore((s) => s.setUser);
  // 未启用任何连接的部署（或接口失败）保持原样：只展示本地账号登录。
  const providersQuery = useQuery({
    queryKey: SSO_PROVIDERS_QUERY_KEY,
    queryFn: fetchSsoProviders,
    retry: false,
  });
  const providers = providersQuery.data ?? [];

  const onFinish = async (values: { username: string; password: string }) => {
    setError(null);
    setLoading(true);
    try {
      const resp = await login(values);
      setTokens(resp.accessToken, resp.refreshToken);
      setUser(resp.user);
      navigate('/workspaces');
    } catch (e) {
      if (e instanceof ApiError) {
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
                  href={`/api/auth/sso/${provider.providerId}/start`}
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
          <div className="auth-entry-link">
            <Link to="/register">{t('login.registerLink')}</Link>
          </div>
        </Form>
      </Card>
    </AuthEntryShell>
  );
}
