import { useState } from 'react';
import { Button, Card, Form, Input, message, Typography } from 'antd';
import { LockOutlined, SaveOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { changePassword } from './profileApi';

const { Text } = Typography;

type PasswordFormValues = {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
};

function getApiMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

export function ChangePasswordPanel() {
  const [form] = Form.useForm<PasswordFormValues>();
  const [successVisible, setSuccessVisible] = useState(false);

  const mutation = useMutation({
    mutationFn: async () => {
      const values = await form.validateFields();
      return changePassword({
        oldPassword: values.oldPassword,
        newPassword: values.newPassword,
      });
    },
    onSuccess: () => {
      form.resetFields();
      setSuccessVisible(true);
      message.success('密码修改成功');
    },
    onError: (error) => {
      message.error(getApiMessage(error, '密码修改失败'));
    },
  });

  return (
    <Card title="账号安全" styles={{ body: { padding: 18 } }}>
      <Form
        form={form}
        layout="vertical"
        disabled={mutation.isPending}
        onFinish={() => mutation.mutate()}
        style={{ maxWidth: 420 }}
      >
        <Form.Item
          label="当前密码"
          name="oldPassword"
          rules={[{ required: true, message: '请输入当前密码' }]}
        >
          <Input.Password
            prefix={<LockOutlined />}
            placeholder="请输入当前密码"
            autoComplete="current-password"
          />
        </Form.Item>

        <Form.Item
          label="新密码"
          name="newPassword"
          rules={[
            { required: true, message: '请输入新密码' },
            { min: 6, message: '密码长度至少 6 位' },
          ]}
        >
          <Input.Password
            prefix={<LockOutlined />}
            placeholder="请输入新密码"
            autoComplete="new-password"
          />
        </Form.Item>

        <Form.Item
          label="确认新密码"
          name="confirmPassword"
          dependencies={['newPassword']}
          rules={[
            { required: true, message: '请再次输入新密码' },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (!value || getFieldValue('newPassword') === value) {
                  return Promise.resolve();
                }
                return Promise.reject(new Error('两次输入的密码不一致'));
              },
            }),
          ]}
        >
          <Input.Password
            prefix={<LockOutlined />}
            placeholder="请再次输入新密码"
            autoComplete="new-password"
          />
        </Form.Item>

        {successVisible ? (
          <Text type="success" style={{ display: 'block', marginBottom: 12 }}>
            密码已成功修改，请使用新密码登录。
          </Text>
        ) : null}

        <Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            icon={<SaveOutlined />}
            loading={mutation.isPending}
          >
            修改密码
          </Button>
        </Form.Item>
      </Form>
    </Card>
  );
}
