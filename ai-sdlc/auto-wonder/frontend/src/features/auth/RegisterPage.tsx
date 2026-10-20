import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { Form, Input, Button, Alert, Card, Typography } from 'antd';
import { CloseCircleFilled, EyeOutlined, EyeInvisibleOutlined, LoadingOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { register } from './api';
import { ApiError } from '@/shared/types/common';
import { AuthEntryShell } from './AuthEntryShell';

const { Title } = Typography;

export function RegisterPage() {
  const { t } = useTranslation('auth');
  const [form] = Form.useForm();
  useLocalizedValidation(form);
  const [error, setError] = useState<{ message: string } | { key: 'register.failed' } | null>(null);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const onFinish = async (values: { username: string; password: string; email: string; nickname: string }) => {
    setError(null);
    setLoading(true);
    try {
      await register(values);
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
    </AuthEntryShell>
  );
}
