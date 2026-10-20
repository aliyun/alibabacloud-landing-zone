import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Input, Typography, message } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { USER_PROFILE_QUERY_KEY, getMyProfile, updateMyProfile, type MyProfile } from './profileApi';
import { useAuthStore } from '@/shared/auth/store';

const { Text } = Typography;

type ProfileFormValues = {
  nickname: string;
  email: string;
  phone: string;
};

const PHONE_PATTERN = /^\+?[0-9][0-9 -]*$/;

function getApiMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function toFormValues(profile: MyProfile): ProfileFormValues {
  return {
    nickname: profile.nickname ?? '',
    email: profile.email ?? '',
    phone: profile.phone ?? '',
  };
}

export function BasicProfilePanel() {
  const { t } = useTranslation('profile');
  const [form] = Form.useForm<ProfileFormValues>();
  const queryClient = useQueryClient();
  const setUser = useAuthStore((s) => s.setUser);
  const [hydrated, setHydrated] = useState(false);

  const profileQuery = useQuery({
    queryKey: USER_PROFILE_QUERY_KEY,
    queryFn: getMyProfile,
    retry: false,
  });
  useLocalizedValidation(form, !profileQuery.isError);
  const profile = profileQuery.data;

  useEffect(() => {
    if (profileQuery.isSuccess && profile && !hydrated) {
      form.setFieldsValue(toFormValues(profile));
      setHydrated(true);
    }
  }, [form, profile, profileQuery.isSuccess, hydrated]);

  const nicknameValue = Form.useWatch('nickname', form) ?? '';
  const emailValue = Form.useWatch('email', form) ?? '';
  const phoneValue = Form.useWatch('phone', form) ?? '';
  const isDirty = hydrated && profile != null && (
    nicknameValue !== (profile.nickname ?? '')
    || emailValue !== (profile.email ?? '')
    || phoneValue !== (profile.phone ?? '')
  );

  const saveMutation = useMutation({
    mutationFn: async () => {
      const values = await form.validateFields();
      return updateMyProfile({
        nickname: values.nickname.trim(),
        email: values.email.trim(),
        phone: values.phone.trim(),
      });
    },
    onSuccess: async (data) => {
      message.success(i18n.t('profile:basic.saved'));
      form.setFieldsValue(toFormValues(data));
      // The authoritative server row refreshes the cache; the auth-store copy keeps its
      // tokens/permissions untouched so the top-bar nickname updates without re-login.
      const current = useAuthStore.getState().user;
      setUser({
        id: current?.id ?? data.id,
        username: current?.username ?? data.username,
        nickname: data.nickname ?? '',
        email: data.email ?? '',
        isAdmin: current?.isAdmin ?? null,
        phone: data.phone,
      });
      await queryClient.invalidateQueries({ queryKey: USER_PROFILE_QUERY_KEY });
    },
    onError: (error) => {
      message.error(getApiMessage(error, i18n.t('profile:basic.saveFailed')));
    },
  });

  const loading = profileQuery.isLoading || saveMutation.isPending;

  if (profileQuery.isError) {
    return (
      <Card title={t('basic.title')} styles={{ body: { padding: 18 } }}>
        <Alert
          type="error"
          showIcon
          message={t('basic.loadFailed')}
          description={getApiMessage(profileQuery.error, t('retryLater'))}
        />
        <Button aria-label={t('retry')}
          style={{ marginTop: 16 }}
          onClick={() => profileQuery.refetch()}
          loading={profileQuery.isFetching}
        >{t('retry')}</Button>
      </Card>
    );
  }

  return (
    <Card title={t('basic.title')} styles={{ body: { padding: 18 } }}>
      <Form
        form={form}
        layout="vertical"
        disabled={loading}
        onFinish={() => saveMutation.mutate()}
      >
        <div style={{ marginBottom: 16 }}>
          <label htmlFor="basic-profile-username" style={{ display: 'inline-block', marginBottom: 8 }}>{t('basic.username')}</label>
          <Input id="basic-profile-username" value={profile?.username ?? ''} disabled readOnly />
          <Text type="secondary" style={{ display: 'block', fontSize: 12, marginTop: 4 }}>{t('basic.usernameHint')}</Text>
        </div>

        <Form.Item
          label={t('basic.nickname')}
          name="nickname"
          rules={[
            { required: true, whitespace: true, message: t('basic.nicknameRequired') },
            { max: 64, message: t('basic.nicknameMax') },
          ]}
        >
          <Input placeholder={t('basic.nicknameRequired')} maxLength={64} autoComplete="off" />
        </Form.Item>

        <Form.Item
          label={t('basic.email')}
          name="email"
          extra={t('basic.emailHint')}
          rules={[
            { type: 'email', message: t('basic.emailInvalid') },
            { max: 128, message: t('basic.emailMax') },
          ]}
        >
          <Input placeholder={t('basic.emailPlaceholder')} maxLength={128} autoComplete="off" />
        </Form.Item>

        <Form.Item
          label={t('basic.phone')}
          name="phone"
          extra={t('basic.phoneHint')}
          rules={[
            { max: 32, message: t('basic.phoneMax') },
            { pattern: PHONE_PATTERN, message: t('basic.phoneInvalid') },
          ]}
        >
          <Input placeholder={t('basic.phonePlaceholder')} maxLength={32} autoComplete="off" />
        </Form.Item>

        <Form.Item style={{ marginBottom: 0 }}>
          <Button aria-label={t('basic.save')}
            type="primary"
            htmlType="submit"
            icon={<SaveOutlined aria-hidden="true" />}
            loading={saveMutation.isPending}
            disabled={!isDirty}
          >{t('basic.save')}</Button>
          {profileQuery.isSuccess && !isDirty ? (
            <Text type="secondary" style={{ marginLeft: 12 }}>{t('basic.unchanged')}</Text>
          ) : null}
        </Form.Item>
      </Form>
    </Card>
  );
}
