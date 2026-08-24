import { useState } from 'react';
import { Form, Input, Button, Alert, Card, Typography } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import { register } from './api';
import { ApiError } from '@/shared/types/common';
import { AuthEntryShell } from './AuthEntryShell';

const { Title } = Typography;

export function RegisterPage() {
  const [error, setError] = useState<string | null>(null);
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
        setError(e.message);
      } else {
        setError('注册失败，请重试');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthEntryShell>
      <Card className="auth-entry-card" variant="borderless">
        <Title level={3} className="auth-entry-title">创建 AutoWonder 账号</Title>
        <p className="auth-entry-subtitle">建立你的工作空间入口，连接工单、仓库和数字员工小队。</p>
        {error && <Alert message={error} type="error" showIcon style={{ marginBottom: 16 }} />}
        <Form onFinish={onFinish} layout="vertical">
          <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="用户名" />
          </Form.Item>
          <Form.Item label="昵称" name="nickname" rules={[{ required: true, message: '请输入昵称' }]}>
            <Input placeholder="昵称" />
          </Form.Item>
          <Form.Item label="邮箱" name="email" rules={[{ required: true, type: 'email', message: '请输入有效邮箱' }]}>
            <Input placeholder="邮箱" />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, min: 6, message: '密码至少6位' }]}>
            <Input.Password placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              注册
            </Button>
          </Form.Item>
          <div className="auth-entry-link">
            <Link to="/login">已有账号？去登录</Link>
          </div>
        </Form>
      </Card>
    </AuthEntryShell>
  );
}
