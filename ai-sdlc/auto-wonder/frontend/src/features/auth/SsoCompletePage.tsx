import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Alert, Button, Card, Form, Input, Spin, Typography } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, LockOutlined, UserOutlined } from '@ant-design/icons';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { i18n } from '@/shared/i18n';
import { useAuthStore } from '@/shared/auth/store';
import { claimFederatedIdentity } from '@/features/profile/federatedApi';
import { AuthEntryShell } from './AuthEntryShell';
import { bindSsoIdentity, exchangeSsoTicket } from './ssoApi';

const { Title, Text } = Typography;

// URL 只携带一次性票据或错误码，令牌永远不进 URL；错误码到文案的映射必须显式列出全部已知值。
const SSO_ERROR_KEYS = {
  '36001': 'sso.error.connectionUnavailable',
  '36002': 'sso.error.verificationFailed',
  '36003': 'sso.error.verificationFailed',
  '36004': 'sso.error.invalid',
  '36005': 'sso.error.invalid',
  '36006': 'sso.error.identityConflict',
  '36007': 'sso.error.accountDisabled',
  '36009': 'sso.error.connectionUnavailable',
  '36012': 'sso.error.ticketInvalid',
} as const;

function ssoErrorKey(code: string | null) {
  if (code && code in SSO_ERROR_KEYS) {
    return SSO_ERROR_KEYS[code as keyof typeof SSO_ERROR_KEYS];
  }
  return 'sso.error.generic' as const;
}

function getApiMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

type ExchangeState = { status: 'idle' } | { status: 'pending' } | { status: 'failed'; message: string };

export function SsoCompletePage() {
  const { t } = useTranslation('auth');
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [form] = Form.useForm<{ username: string; password: string }>();
  useLocalizedValidation(form);

  const ticket = searchParams.get('ticket');
  const bindTicket = searchParams.get('bind');
  const bound = searchParams.get('bound');
  const errorCode = searchParams.get('error');

  const accessToken = useAuthStore((s) => s.accessToken);
  const setTokens = useAuthStore((s) => s.setTokens);
  const setUser = useAuthStore((s) => s.setUser);

  const [exchange, setExchange] = useState<ExchangeState>({ status: 'idle' });
  const [claimError, setClaimError] = useState<string | null>(null);
  const [claimed, setClaimed] = useState(false);
  const exchangeStartedRef = useRef(false);

  useEffect(() => {
    if (!ticket || exchangeStartedRef.current) return;
    // 票据一次性：重复消费只会失败，严格保证每个组件实例只兑换一次。
    exchangeStartedRef.current = true;
    setExchange({ status: 'pending' });
    exchangeSsoTicket(ticket)
      .then((resp) => {
        setTokens(resp.accessToken, resp.refreshToken);
        setUser(resp.user);
        navigate('/workspaces', { replace: true });
      })
      .catch((error: unknown) => {
        setExchange({ status: 'failed', message: getApiMessage(error, i18n.t('auth:sso.error.ticketInvalid')) });
      });
  }, [ticket, navigate, setTokens, setUser]);

  const bindMutation = useMutation({
    mutationFn: (values: { username: string; password: string }) =>
      bindSsoIdentity({ ticket: bindTicket ?? '', username: values.username.trim(), password: values.password }),
    onSuccess: (resp) => {
      setTokens(resp.accessToken, resp.refreshToken);
      setUser(resp.user);
      navigate('/workspaces', { replace: true });
    },
  });

  const claimMutation = useMutation({
    mutationFn: () => claimFederatedIdentity(bindTicket ?? ''),
    onSuccess: () => setClaimed(true),
    onError: (error) => setClaimError(getApiMessage(error, i18n.t('auth:sso.error.generic'))),
  });

  const backToLogin = () => navigate('/login', { replace: true });

  const errorView = (message: string): ReactNode => (
    <div style={{ display: 'grid', gap: 16 }} data-testid="sso-error">
      <Title level={3} className="auth-entry-title" style={{ margin: 0 }}>
        <CloseCircleFilled aria-hidden="true" style={{ color: '#ff4d4f', marginRight: 8 }} />
        {t('sso.errorTitle')}
      </Title>
      <Alert type="error" showIcon message={message} description={t('sso.errorHint')} />
      <Button type="primary" block onClick={backToLogin}>{t('sso.backToLogin')}</Button>
    </div>
  );

  const boundView = (): ReactNode => (
    <div style={{ display: 'grid', gap: 16, justifyItems: 'center', textAlign: 'center' }} data-testid="sso-bound">
      <CheckCircleFilled aria-hidden="true" style={{ fontSize: 40, color: '#52c41a' }} />
      <Title level={3} className="auth-entry-title" style={{ margin: 0 }}>{t('sso.boundTitle')}</Title>
      <Text type="secondary">{claimed ? t('sso.claimed') : t('sso.boundDescription')}</Text>
      <Button
        type="primary"
        onClick={() => navigate(accessToken ? '/profile/settings?tab=federated' : '/login', { replace: true })}
      >
        {accessToken ? t('sso.toProfile') : t('sso.backToLogin')}
      </Button>
    </div>
  );

  const claimView = (): ReactNode => (
    <div style={{ display: 'grid', gap: 16 }} data-testid="sso-claim">
      <Title level={3} className="auth-entry-title" style={{ margin: 0 }}>{t('sso.claimTitle')}</Title>
      <Text type="secondary">{t('sso.claimDescription')}</Text>
      {claimError ? <Alert type="error" showIcon message={claimError} /> : null}
      <Button
        type="primary"
        block
        loading={claimMutation.isPending}
        onClick={() => {
          setClaimError(null);
          claimMutation.mutate();
        }}
      >
        {t('sso.claimSubmit')}
      </Button>
      <Alert type="warning" showIcon message={t('sso.noWorkspaceNotice')} />
      <Link to="/login">{t('sso.switchLogin')}</Link>
    </div>
  );

  const bindView = (): ReactNode => (
    <div style={{ display: 'grid', gap: 16 }} data-testid="sso-bind">
      <Title level={3} className="auth-entry-title" style={{ margin: 0 }}>{t('sso.bindTitle')}</Title>
      <Text type="secondary">{t('sso.bindDescription')}</Text>
      {bindMutation.isError ? (
        <Alert type="error" showIcon message={getApiMessage(bindMutation.error, t('sso.error.generic'))} />
      ) : null}
      <Form form={form} layout="vertical" noValidate onFinish={(values) => bindMutation.mutate(values)}>
        <Form.Item label={t('fields.username')} name="username" rules={[{ required: true, message: t('fields.usernameRequired') }]}>
          <Input autoComplete="username" prefix={<UserOutlined aria-hidden="true" />} placeholder={t('fields.username')} />
        </Form.Item>
        <Form.Item label={t('fields.password')} name="password" rules={[{ required: true, message: t('fields.passwordRequired') }]}>
          <Input.Password autoComplete="current-password" prefix={<LockOutlined aria-hidden="true" />} placeholder={t('fields.password')} />
        </Form.Item>
        <Button type="primary" htmlType="submit" block loading={bindMutation.isPending}>{t('sso.bindSubmit')}</Button>
      </Form>
      <Alert type="warning" showIcon message={t('sso.noWorkspaceNotice')} />
      <Link to="/login">{t('sso.switchLogin')}</Link>
    </div>
  );

  let content: ReactNode;
  if (ticket) {
    content = exchange.status === 'failed'
      ? errorView(exchange.message)
      : (
        <div style={{ display: 'grid', gap: 12, justifyItems: 'center', padding: '24px 0' }} data-testid="sso-completing">
          <Spin size="large" />
          <Text type="secondary">{t('sso.completing')}</Text>
        </div>
      );
  } else if (bindTicket) {
    content = claimed ? boundView() : accessToken ? claimView() : bindView();
  } else if (bound) {
    content = boundView();
  } else {
    content = errorView(t(ssoErrorKey(errorCode)));
  }

  return (
    <AuthEntryShell>
      <Card className="auth-entry-card" variant="borderless">{content}</Card>
    </AuthEntryShell>
  );
}
