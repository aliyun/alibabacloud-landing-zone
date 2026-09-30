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
  const [form] = Form.useForm<ProfileFormValues>();
  const queryClient = useQueryClient();
  const setUser = useAuthStore((s) => s.setUser);
  const [hydrated, setHydrated] = useState(false);

  const profileQuery = useQuery({
    queryKey: USER_PROFILE_QUERY_KEY,
    queryFn: getMyProfile,
    retry: false,
  });
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
      message.success('基本信息已保存');
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
      message.error(getApiMessage(error, '基本信息保存失败'));
    },
  });

  const loading = profileQuery.isLoading || saveMutation.isPending;

  if (profileQuery.isError) {
    return (
      <Card title="基本信息" styles={{ body: { padding: 18 } }}>
        <Alert
          type="error"
          showIcon
          message="基本信息加载失败"
          description={getApiMessage(profileQuery.error, '请稍后重试')}
        />
        <Button
          style={{ marginTop: 16 }}
          onClick={() => profileQuery.refetch()}
          loading={profileQuery.isFetching}
        >
          重试
        </Button>
      </Card>
    );
  }

  return (
    <Card title="基本信息" styles={{ body: { padding: 18 } }}>
      <Form
        form={form}
        layout="vertical"
        disabled={loading}
        onFinish={() => saveMutation.mutate()}
      >
        <div style={{ marginBottom: 16 }}>
          <label htmlFor="basic-profile-username" style={{ display: 'inline-block', marginBottom: 8 }}>
            用户名
          </label>
          <Input id="basic-profile-username" value={profile?.username ?? ''} disabled readOnly />
          <Text type="secondary" style={{ display: 'block', fontSize: 12, marginTop: 4 }}>
            用户名是登录标识，不可修改。
          </Text>
        </div>

        <Form.Item
          label="昵称"
          name="nickname"
          rules={[
            { required: true, whitespace: true, message: '请输入昵称' },
            { max: 64, message: '昵称最多 64 个字符' },
          ]}
        >
          <Input placeholder="请输入昵称" maxLength={64} autoComplete="off" />
        </Form.Item>

        <Form.Item
          label="邮箱"
          name="email"
          extra="可用于成员与账号展示；清空保存即删除邮箱。"
          rules={[
            { type: 'email', message: '邮箱格式不正确' },
            { max: 128, message: '邮箱最多 128 个字符' },
          ]}
        >
          <Input placeholder="例如 alice@example.com" maxLength={128} autoComplete="off" />
        </Form.Item>

        <Form.Item
          label="联系方式"
          name="phone"
          extra="支持国际区号、空格与连字符；清空保存即删除联系方式。"
          rules={[
            { max: 32, message: '联系方式最多 32 个字符' },
            { pattern: PHONE_PATTERN, message: '联系方式仅支持数字、空格、连字符，可带国际区号前缀 +' },
          ]}
        >
          <Input placeholder="例如 +86 138-0000-0000" maxLength={32} autoComplete="off" />
        </Form.Item>

        <Form.Item style={{ marginBottom: 0 }}>
          <Button
            type="primary"
            htmlType="submit"
            icon={<SaveOutlined />}
            loading={saveMutation.isPending}
            disabled={!isDirty}
          >
            保存修改
          </Button>
          {profileQuery.isSuccess && !isDirty ? (
            <Text type="secondary" style={{ marginLeft: 12 }}>未修改</Text>
          ) : null}
        </Form.Item>
      </Form>
    </Card>
  );
}
