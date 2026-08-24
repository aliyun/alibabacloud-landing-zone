import { useState } from 'react';
import { Form, Input, Button, Alert, Card, Typography } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { login } from './api';
import { useAuthStore } from '@/shared/auth/store';
import { ApiError } from '@/shared/types/common';
import { AuthEntryShell } from './AuthEntryShell';

const { Title } = Typography;

export function LoginPage() {
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const setTokens = useAuthStore((s) => s.setTokens);
  const setUser = useAuthStore((s) => s.setUser);

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
        setError(e.message);
      } else {
        setError('登录失败，请重试');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthEntryShell>
      <Card className="auth-entry-card" variant="borderless">
        <Title level={3} className="auth-entry-title">欢迎回来</Title>
        <p className="auth-entry-subtitle">继续推进你的自主交付工作台</p>
        {error && <Alert message={error} type="error" showIcon style={{ marginBottom: 16 }} />}
        <Form onFinish={onFinish} layout="vertical">
          <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              登录
            </Button>
          </Form.Item>
          <div className="auth-entry-link">
            <Link to="/register">没有账号？去注册</Link>
          </div>
        </Form>
      </Card>
    </AuthEntryShell>
  );
}
