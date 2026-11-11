import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { Form, Input, Button, Alert, Card, Modal, Typography } from 'antd';
import { CloseCircleFilled, EyeOutlined, EyeInvisibleOutlined, LoadingOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { register } from './api';
import { SSO_LOGIN_CONFIG_QUERY_KEY, fetchSsoLoginConfig } from './ssoApi';
import { ApiError } from '@/shared/types/common';
import type { AdministratorContact } from '@/shared/types/administrator';
import { AdministratorContactList } from '@/shared/ui/AdministratorContactList';
import { AuthEntryShell } from './AuthEntryShell';

const { Title } = Typography;

export function RegisterPage() {
  const { t } = useTranslation('auth');
  const [form] = Form.useForm();
  useLocalizedValidation(form);
  const [error, setError] = useState<{ message: string } | { key: 'register.failed' } | null>(null);
  const [pendingContacts, setPendingContacts] = useState<AdministratorContact[] | null>(null);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  // 注册配置读取失败时保持原有注册表单；服务端仍会独立拒绝已关闭的注册。
  const configQuery = useQuery({
    queryKey: SSO_LOGIN_CONFIG_QUERY_KEY,
    queryFn: fetchSsoLoginConfig,
    retry: false,
  });
  const registrationEnabled = configQuery.data?.registrationEnabled ?? true;

  if (!registrationEnabled) {
    return (
      <AuthEntryShell>
        <Card className="auth-entry-card" variant="borderless">
          <Title level={3} className="auth-entry-title">{t('register.title')}</Title>
          <Alert
            type="warning"
            showIcon
            message={t('register.disabled')}
            description={t('register.disabledHint')}
            style={{ marginBottom: 16 }}
          />
          <Link to="/login?local=1">
            <Button block>{t('register.backToLogin')}</Button>
          </Link>
        </Card>
      </AuthEntryShell>
    );
  }

  const onFinish = async (values: { username: string; password: string; email: string; nickname: string }) => {
    setError(null);
    setLoading(true);
    try {
      const created = await register(values);
      if (created.reviewRequired) {
        // 待审：先弹窗展示管理员联系方式，确定后进入登录页。
        setPendingContacts(created.administratorContacts ?? []);
        return;
      }
      navigate('/login');
    } catch (e) {
      if (e instanceof ApiError) {
        setError({ message: e.message });
      } else {
        setError({ key: 'register.failed' });
      }
    } finally {
      setLoading(false);
    }
  };

  const closePending = () => {
    setPendingContacts(null);
    navigate('/login');
  };

  const errorText = error && ('key' in error ? t(error.key) : error.message);
  return (
    <AuthEntryShell>
      <Card className="auth-entry-card" variant="borderless">
        <Title level={3} className="auth-entry-title">{t('register.title')}</Title>
        <p className="auth-entry-subtitle">{t('register.description')}</p>
        {error && <Alert message={errorText} type="error" showIcon icon={<CloseCircleFilled aria-hidden="true" />} style={{ marginBottom: 16 }} />}
        <Form form={form} onFinish={onFinish} layout="vertical">
          <Form.Item label={t('fields.username')} name="username" rules={[{ required: true, message: t('fields.usernameRequired') }]}>
            <Input placeholder={t('fields.username')} />
          </Form.Item>
          <Form.Item label={t('fields.nickname')} name="nickname" rules={[{ required: true, message: t('fields.nicknameRequired') }]}>
            <Input placeholder={t('fields.nickname')} />
          </Form.Item>
          <Form.Item label={t('fields.email')} name="email" rules={[{ required: true, type: 'email', message: t('fields.emailRequired') }]}>
            <Input placeholder={t('fields.email')} />
          </Form.Item>
          <Form.Item label={t('fields.password')} name="password" rules={[{ required: true, min: 6, message: t('fields.passwordLength') }]}>
            <Input.Password iconRender={visible => <Button type="text" htmlType="button" aria-label={t(visible ? 'fields.hidePassword' : 'fields.showPassword')}
              icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />} style={{ padding: 0, width: 16, height: 16 }} />}
              placeholder={t('fields.password')} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" aria-label={t('register.submit')} loading={loading ? { icon: <LoadingOutlined aria-hidden="true" /> } : false} block>{t('register.submit')}</Button>
          </Form.Item>
          <div className="auth-entry-link">
            <Link to="/login">{t('register.loginLink')}</Link>
          </div>
        </Form>
      </Card>
      <Modal
        open={pendingContacts !== null}
        title={t('register.pendingTitle')}
        closable={false}
        maskClosable={false}
        onOk={closePending}
        okText={t('login.pendingOk')}
        cancelButtonProps={{ style: { display: 'none' } }}
      >
        <p>{t('register.pendingHint')}</p>
        <AdministratorContactList
          contacts={pendingContacts ?? []}
          title={t('contacts.title')}
          emptyText={t('contacts.empty')}
        />
      </Modal>
    </AuthEntryShell>
  );
}
