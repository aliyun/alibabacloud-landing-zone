import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { useState } from 'react';
import { Button, Card, Form, Input, message, Typography } from 'antd';
import { EyeOutlined, EyeInvisibleOutlined, LockOutlined, SaveOutlined } from '@ant-design/icons';
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
  const { t } = useTranslation('profile');
  const [form] = Form.useForm<PasswordFormValues>();
  useLocalizedValidation(form);
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
      message.success(i18n.t('profile:password.success'));
    },
    onError: (error) => {
      message.error(getApiMessage(error, i18n.t('profile:password.failed')));
    },
  });

  return (
    <Card title={t('password.title')} styles={{ body: { padding: 18 } }}>
      <Form
        form={form}
        layout="vertical"
        disabled={mutation.isPending}
        onFinish={() => mutation.mutate()}
      >
        <Form.Item
          label={t('password.current')}
          name="oldPassword"
          rules={[{ required: true, message: t('password.currentRequired') }]}
        >
          <Input.Password
            iconRender={visible => <Button type="text" htmlType="button" aria-label={t(visible ? 'password.hide' : 'password.show')} disabled={mutation.isPending}
              icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />} style={{ padding: 0, width: 16, height: 16 }} />}
            prefix={<LockOutlined aria-hidden="true" />}
            placeholder={t('password.currentRequired')}
            autoComplete="current-password"
          />
        </Form.Item>

        <Form.Item
          label={t('password.new')}
          name="newPassword"
          rules={[
            { required: true, message: t('password.newRequired') },
            { min: 6, message: t('password.min') },
          ]}
        >
          <Input.Password
            iconRender={visible => <Button type="text" htmlType="button" aria-label={t(visible ? 'password.hide' : 'password.show')} disabled={mutation.isPending}
              icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />} style={{ padding: 0, width: 16, height: 16 }} />}
            prefix={<LockOutlined aria-hidden="true" />}
            placeholder={t('password.newRequired')}
            autoComplete="new-password"
          />
        </Form.Item>

        <Form.Item
          label={t('password.confirm')}
          name="confirmPassword"
          dependencies={['newPassword']}
          rules={[
            { required: true, message: t('password.confirmRequired') },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (!value || getFieldValue('newPassword') === value) {
                  return Promise.resolve();
                }
                return Promise.reject(new Error(t('password.mismatch')));
              },
            }),
          ]}
        >
          <Input.Password
            iconRender={visible => <Button type="text" htmlType="button" aria-label={t(visible ? 'password.hide' : 'password.show')} disabled={mutation.isPending}
              icon={visible ? <EyeOutlined aria-hidden="true" /> : <EyeInvisibleOutlined aria-hidden="true" />} style={{ padding: 0, width: 16, height: 16 }} />}
            prefix={<LockOutlined aria-hidden="true" />}
            placeholder={t('password.confirmRequired')}
            autoComplete="new-password"
          />
        </Form.Item>

        {successVisible ? (
          <Text type="success" style={{ display: 'block', marginBottom: 12 }}>{t('password.successHint')}</Text>
        ) : null}

        <Form.Item>
          <Button aria-label={t('password.submit')}
            type="primary"
            htmlType="submit"
            icon={<SaveOutlined aria-hidden="true" />}
            loading={mutation.isPending}
          >{t('password.submit')}</Button>
        </Form.Item>
      </Form>
    </Card>
  );
}
