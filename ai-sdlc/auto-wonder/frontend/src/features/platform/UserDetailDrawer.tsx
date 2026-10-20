import { useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Descriptions,
  Divider,
  Drawer,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { CloseCircleFilled, ExclamationCircleFilled } from '@ant-design/icons';
import type { TFunction } from 'i18next';
import type { ColumnsType } from 'antd/es/table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Table } from '@/shared/theme/ThemedTable';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { formatDateTime } from '@/shared/i18n/format';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useAllWorkspaces } from '@/features/auth/workspaceDiscoveryApi';
import {
  USER_ADMIN_QUERY_KEY_ROOT,
  addUserAdminWorkspace,
  deleteUserAdmin,
  getUserAdminDetail,
  listUserAdminImIdentities,
  listUserAdminWorkspaces,
  removeUserAdminWorkspace,
  resetUserAdminPassword,
  restoreUserAdmin,
  sendUserAdminImTest,
  updateUserAdminImIdentity,
  updateUserAdminProfile,
  updateUserAdminWorkspaceLevel,
  userAdminDetailQueryKey,
  userAdminImQueryKey,
  userAdminWorkspacesQueryKey,
  type UserAdminImIdentity,
  type UserAdminStatus,
  type UserAdminWorkspace,
  type WorkspaceAccessLevel,
} from './userAdminApi';

const { Paragraph, Text } = Typography;

const EMPTY_TEXT = '—';
const PHONE_PATTERN = /^\+?[0-9][0-9 -]*$/;

const statusKeys = {
  NORMAL: { labelKey: 'statusNormal', color: 'success' },
  DELETED: { labelKey: 'statusDeleted', color: 'default' },
  DEACTIVATED: { labelKey: 'statusDeactivated', color: 'orange' },
} as const;

const levelKeys = {
  READ_ONLY: 'levelReadOnly',
  READ_WRITE: 'levelReadWrite',
  ADMIN: 'levelAdmin',
} as const;

const levelOptions = (t: TFunction<'userManagement'>) => [
  { value: 'READ_ONLY' as WorkspaceAccessLevel, label: t('levelReadOnly') },
  { value: 'READ_WRITE' as WorkspaceAccessLevel, label: t('levelReadWrite') },
  { value: 'ADMIN' as WorkspaceAccessLevel, label: t('levelAdmin') },
];

export type UserDetailInitialAction = 'delete' | 'restore';

interface Props {
  userId: number | null;
  open: boolean;
  initialAction?: UserDetailInitialAction;
  onClose: () => void;
  onChanged: () => void;
}

type EditFormValues = {
  nickname: string;
  email: string;
  phone: string;
};

type PasswordFormValues = {
  newPassword: string;
  confirmPassword: string;
};

export function UserDetailDrawer({ userId, open, initialAction, onClose, onChanged }: Props) {
  const { t } = useTranslation('userManagement');
  const runWithAccess = useAccessCommand();
  const queryClient = useQueryClient();
  const [editForm] = Form.useForm<EditFormValues>();
  const [passwordForm] = Form.useForm<PasswordFormValues>();
  useLocalizedValidation(editForm);
  useLocalizedValidation(passwordForm);

  const [editOpen, setEditOpen] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [restoreOpen, setRestoreOpen] = useState(false);
  const [deleteInput, setDeleteInput] = useState('');
  const [restoreError, setRestoreError] = useState<string | null>(null);
  const [imInput, setImInput] = useState('');
  const [handledAction, setHandledAction] = useState<string | null>(null);

  const detailQuery = useQuery({
    queryKey: userAdminDetailQueryKey(userId ?? 0),
    queryFn: () => getUserAdminDetail(userId as number),
    enabled: open && userId != null,
    retry: false,
  });
  const detail = detailQuery.data;
  const status: UserAdminStatus | undefined = detail?.status;
  const editable = detail?.editable === true;
  const restorable = detail?.restorable === true;
  const relatedReadable = open && userId != null && detail != null && detail.status !== 'DELETED';

  const workspacesQuery = useQuery({
    queryKey: userAdminWorkspacesQueryKey(userId ?? 0),
    queryFn: () => listUserAdminWorkspaces(userId as number),
    enabled: relatedReadable,
    retry: false,
  });

  const imQuery = useQuery({
    queryKey: userAdminImQueryKey(userId ?? 0),
    queryFn: () => listUserAdminImIdentities(userId as number),
    enabled: relatedReadable,
    retry: false,
  });

  const identity: UserAdminImIdentity | undefined = imQuery.data?.[0];

  useEffect(() => {
    if (imQuery.data) {
      setImInput(imQuery.data[0]?.externalUserId ?? '');
    }
  }, [imQuery.data]);

  useEffect(() => {
    if (!open) {
      setHandledAction(null);
      setEditOpen(false);
      setPasswordOpen(false);
      setDeleteOpen(false);
      setRestoreOpen(false);
      setDeleteInput('');
      setRestoreError(null);
    }
  }, [open]);

  useEffect(() => {
    if (!open || !initialAction || detail == null) return;
    const token = `${initialAction}:${detail.id}`;
    if (handledAction === token) return;
    setHandledAction(token);
    if (initialAction === 'delete') setDeleteOpen(true);
    if (initialAction === 'restore') setRestoreOpen(true);
  }, [open, initialAction, detail, handledAction]);

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: USER_ADMIN_QUERY_KEY_ROOT });
    onChanged();
  };

  const editMutation = useMutation({
    mutationFn: (values: EditFormValues) =>
      updateUserAdminProfile(detail?.id as number, {
        nickname: values.nickname.trim(),
        email: values.email.trim(),
        phone: values.phone.trim(),
      }),
    onSuccess: () => {
      message.success(i18n.t('userManagement:profileSaved'));
      setEditOpen(false);
      refresh();
    },
    onError: (error: Error) => message.error(error.message || i18n.t('userManagement:profileSaveFailed')),
  });

  const passwordMutation = useMutation({
    mutationFn: (values: PasswordFormValues) =>
      resetUserAdminPassword(detail?.id as number, {
        newPassword: values.newPassword,
        confirmPassword: values.confirmPassword,
      }),
    onSuccess: () => {
      message.success(i18n.t('userManagement:passwordReset'));
      setPasswordOpen(false);
      passwordForm.resetFields();
      refresh();
    },
    onError: (error: Error) => message.error(error.message || i18n.t('userManagement:passwordResetFailed')),
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteUserAdmin(detail?.id as number, deleteInput),
    onSuccess: () => {
      message.success(i18n.t('userManagement:deleteSuccess'));
      setDeleteOpen(false);
      setDeleteInput('');
      refresh();
    },
    onError: (error: Error) => message.error(error.message || i18n.t('userManagement:deleteFailed')),
  });

  const restoreMutation = useMutation({
    mutationFn: () => restoreUserAdmin(detail?.id as number),
    onSuccess: () => {
      message.success(i18n.t('userManagement:restoreSuccess'));
      setRestoreOpen(false);
      setRestoreError(null);
      refresh();
    },
    onError: (error: Error) => setRestoreError(error.message || i18n.t('userManagement:restoreFailed')),
  });

  const levelMutation = useMutation({
    mutationFn: (payload: { workspaceId: number; accessLevel: WorkspaceAccessLevel }) =>
      updateUserAdminWorkspaceLevel(detail?.id as number, payload.workspaceId, payload.accessLevel),
    onSuccess: (list) => {
      message.success(i18n.t('userManagement:workspaceLevelUpdated'));
      queryClient.setQueryData(userAdminWorkspacesQueryKey(detail?.id as number), list);
    },
    onError: (error: Error) => message.error(error.message || i18n.t('userManagement:workspaceLevelUpdateFailed')),
  });

  const removeMutation = useMutation({
    mutationFn: (workspaceId: number) => removeUserAdminWorkspace(detail?.id as number, workspaceId),
    onSuccess: (list) => {
      message.success(i18n.t('userManagement:workspaceRemoved'));
      queryClient.setQueryData(userAdminWorkspacesQueryKey(detail?.id as number), list);
    },
    onError: (error: Error) => message.error(error.message || i18n.t('userManagement:workspaceRemoveFailed')),
  });

  const imSaveMutation = useMutation({
    mutationFn: () => updateUserAdminImIdentity(detail?.id as number, identity?.provider as string, imInput.trim()),
    onSuccess: () => {
      message.success(i18n.t('userManagement:imSaved'));
      refresh();
    },
    onError: (error: Error) => message.error(error.message || i18n.t('userManagement:imSaveFailed')),
  });

  const imTestMutation = useMutation({
    mutationFn: () => sendUserAdminImTest(detail?.id as number, identity?.provider as string),
    onSuccess: () => message.success(i18n.t('userManagement:imTestSent')),
    onError: (error: Error) => message.error(error.message || i18n.t('userManagement:imTestFailed')),
  });

  const openEdit = () => {
    editForm.setFieldsValue({
      nickname: detail?.nickname ?? '',
      email: detail?.email ?? '',
      phone: detail?.phone ?? '',
    });
    setEditOpen(true);
  };

  const openPassword = () => {
    passwordForm.resetFields();
    setPasswordOpen(true);
  };

  const dateTimeText = (value: string | null | undefined) =>
    value ? formatDateTime(value, { dateStyle: 'medium', timeStyle: 'medium' }) : EMPTY_TEXT;

  const renderStatusTag = (value: UserAdminStatus | undefined) => {
    if (!value || !Object.prototype.hasOwnProperty.call(statusKeys, value)) {
      return <Tag>{value ?? EMPTY_TEXT}</Tag>;
    }
    const meta = statusKeys[value];
    return <Tag color={meta.color}>{t(meta.labelKey)}</Tag>;
  };

  const renderLevel = (level: WorkspaceAccessLevel) => {
    const key = Object.prototype.hasOwnProperty.call(levelKeys, level) ? levelKeys[level] : null;
    return key ? t(key) : level;
  };

  const removeActionColumn: ColumnsType<UserAdminWorkspace>[number] | null = editable
    ? {
        title: t('colActions'),
        key: 'actions',
        width: 100,
        render: (_, record) => {
          const button = (
            <Button type="link" size="small" danger aria-label={t('workspaceRemove')} disabled={record.isOwner === true}>
              {t('workspaceRemove')}
            </Button>
          );
          return record.isOwner ? (
            <Tooltip title={t('ownerProtected')}><span>{button}</span></Tooltip>
          ) : (
            <Popconfirm
              icon={<ExclamationCircleFilled aria-hidden="true" />}
              title={t('workspaceRemoveConfirm')}
              okText={t('confirmDelete')}
              cancelText={t('cancel')}
              onConfirm={() =>
                runWithAccess('ADMIN', t('workspaceRemoveCommand'), () => removeMutation.mutate(record.workspaceId))
              }
            >
              {button}
            </Popconfirm>
          );
        },
      }
    : null;

  const workspaceColumns: ColumnsType<UserAdminWorkspace> = [
    {
      title: t('colWorkspace'),
      dataIndex: 'name',
      render: (_, record) => (
        <Space size={4}>
          <span>{record.name}</span>
          {record.isOwner ? <Tag color="gold">{t('ownerTag')}</Tag> : null}
        </Space>
      ),
    },
    {
      title: t('levelSelect'),
      key: 'level',
      width: 140,
      render: (_, record) =>
        editable ? (
          <Select<WorkspaceAccessLevel>
            size="small"
            aria-label={t('levelSelect')}
            value={record.accessLevel}
            style={{ width: 110 }}
            disabled={record.isOwner || levelMutation.isPending}
            onChange={(value) => levelMutation.mutate({ workspaceId: record.workspaceId, accessLevel: value })}
            options={levelOptions(t)}
          />
        ) : (
          <Tag>{renderLevel(record.accessLevel)}</Tag>
        ),
    },
    {
      title: t('joinedAt'),
      dataIndex: 'joinedAt',
      width: 180,
      render: (value: string | null) => dateTimeText(value),
    },
    ...(removeActionColumn ? [removeActionColumn] : []),
  ];

  const basicItems = detail
    ? [
        { key: 'username', label: t('colUsername'), children: detail.username || EMPTY_TEXT },
        { key: 'nickname', label: t('colNickname'), children: detail.nickname || EMPTY_TEXT },
        { key: 'email', label: t('colEmail'), children: detail.email || EMPTY_TEXT },
        { key: 'phone', label: t('colPhone'), children: detail.phone || EMPTY_TEXT },
        { key: 'status', label: t('colStatus'), children: renderStatusTag(detail.status) },
        {
          key: 'type',
          label: t('colType'),
          children: detail.isAdmin ? <Tag color="blue">{t('typeAdmin')}</Tag> : t('typeUser'),
        },
        { key: 'createdAt', label: t('colCreatedAt'), children: dateTimeText(detail.createdAt) },
        { key: 'lastLoginAt', label: t('colLastLoginAt'), children: dateTimeText(detail.lastLoginAt) },
        ...(status !== 'NORMAL'
          ? [
              { key: 'deletedAt', label: t('fieldDeletedAt'), children: dateTimeText(detail.deletedAt) },
              { key: 'deletedBy', label: t('fieldDeletedBy'), children: detail.deletedByName || EMPTY_TEXT },
            ]
          : []),
      ]
    : [];

  return (
    <Drawer
      title={t('drawerTitle')}
      closable={{ 'aria-label': t('close') }}
      width="min(680px, 94vw)"
      open={open}
      onClose={onClose}
      maskClosable
    >
      {detailQuery.isError ? (
        <Alert
          type="error"
          showIcon
          icon={<CloseCircleFilled aria-hidden="true" />}
          message={t('loadFailed')}
          description={detailQuery.error instanceof Error ? detailQuery.error.message : t('retryLater')}
          action={
            <Button size="small" onClick={() => detailQuery.refetch()} loading={detailQuery.isFetching}>
              {t('retry')}
            </Button>
          }
        />
      ) : null}

      {detail ? (
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <div>
            <Space wrap>
              <Button type="primary" disabled={!editable} onClick={openEdit}>{t('editProfile')}</Button>
              <Button disabled={!editable} onClick={openPassword}>{t('resetPassword')}</Button>
              <Button danger disabled={!editable} onClick={() => { setDeleteInput(''); setDeleteOpen(true); }}>
                {t('deleteUser')}
              </Button>
              {restorable ? (
                <Button onClick={() => { setRestoreError(null); setRestoreOpen(true); }}>{t('restoreUser')}</Button>
              ) : null}
            </Space>
            {!editable ? (
              <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>{t('readOnlyHint')}</Paragraph>
            ) : null}
            <Divider orientation="left" plain>{t('sectionBasic')}</Divider>
            <Descriptions column={1} size="small" bordered items={basicItems} />
          </div>

          <div>
            <Divider orientation="left" plain>{t('sectionWorkspaces')}</Divider>
            {status === 'DELETED' ? (
              <Alert type="warning" showIcon message={t('workspaceDeletedHint')} />
            ) : (
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                {status === 'DEACTIVATED' ? <Alert type="warning" showIcon message={t('workspaceDeactivatedHint')} /> : null}
                <Table<UserAdminWorkspace>
                  rowKey="workspaceId"
                  size="small"
                  columns={workspaceColumns}
                  dataSource={workspacesQuery.data ?? []}
                  loading={workspacesQuery.isLoading}
                  pagination={false}
                  locale={{ emptyText: t('workspaceEmpty') }}
                />
                {editable ? (
                  <AddWorkspaceForm
                    userId={detail.id}
                    existingIds={(workspacesQuery.data ?? []).map((item) => item.workspaceId)}
                    onAdded={(list) => queryClient.setQueryData(userAdminWorkspacesQueryKey(detail.id), list)}
                  />
                ) : null}
              </Space>
            )}
          </div>

          <div>
            <Divider orientation="left" plain>{t('sectionIm')}</Divider>
            {status === 'DELETED' ? (
              <Alert type="warning" showIcon message={t('imDeletedHint')} />
            ) : (
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                {status === 'DEACTIVATED' ? <Alert type="warning" showIcon message={t('imDeactivatedHint')} /> : null}
                <Text type="secondary" style={{ fontSize: 12 }}>{t('imHint')}</Text>
                <Space.Compact style={{ width: '100%' }}>
                  <Input
                    aria-label={t('imLabel')}
                    placeholder={t('imPlaceholder')}
                    maxLength={256}
                    value={imInput}
                    disabled={!editable}
                    onChange={(event) => setImInput(event.target.value)}
                  />
                  <Button
                    type="primary"
                    disabled={!editable || !identity}
                    loading={imSaveMutation.isPending}
                    onClick={() => imSaveMutation.mutate()}
                  >
                    {t('save')}
                  </Button>
                  <Button
                    disabled={!editable || !identity?.testAvailable}
                    loading={imTestMutation.isPending}
                    onClick={() => imTestMutation.mutate()}
                  >
                    {t('imTest')}
                  </Button>
                </Space.Compact>
                {identity && !identity.platformReady ? (
                  <Text type="secondary">{t('imNotReady')}</Text>
                ) : editable && !identity?.testAvailable ? (
                  <Text type="secondary">{t('imTestUnavailable')}</Text>
                ) : null}
              </Space>
            )}
          </div>
        </Space>
      ) : null}

      <Modal
        title={t('editTitle')}
        open={editOpen}
        forceRender
        okText={t('save')}
        cancelText={t('cancel')}
        confirmLoading={editMutation.isPending}
        onOk={() => {
          void editForm.validateFields().then((values) => editMutation.mutate(values)).catch(() => {});
        }}
        onCancel={() => setEditOpen(false)}
      >
        <Form form={editForm} layout="vertical">
          <Form.Item
            label={t('nickname')}
            name="nickname"
            rules={[
              { required: true, whitespace: true, message: t('nicknameRequired') },
              { max: 64, message: t('nicknameMax') },
            ]}
          >
            <Input placeholder={t('nicknameRequired')} maxLength={64} autoComplete="off" />
          </Form.Item>
          <Form.Item
            label={t('email')}
            name="email"
            rules={[
              { type: 'email', message: t('emailInvalid') },
              { max: 128, message: t('emailMax') },
            ]}
          >
            <Input placeholder={t('emailInvalid')} maxLength={128} autoComplete="off" />
          </Form.Item>
          <Form.Item
            label={t('phone')}
            name="phone"
            rules={[
              { max: 32, message: t('phoneMax') },
              { pattern: PHONE_PATTERN, message: t('phoneInvalid') },
            ]}
          >
            <Input placeholder={t('phoneInvalid')} maxLength={32} autoComplete="off" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('passwordTitle')}
        open={passwordOpen}
        forceRender
        okText={t('save')}
        cancelText={t('cancel')}
        confirmLoading={passwordMutation.isPending}
        onOk={() => {
          void passwordForm.validateFields().then((values) => passwordMutation.mutate(values)).catch(() => {});
        }}
        onCancel={() => setPasswordOpen(false)}
      >
        <Form form={passwordForm} layout="vertical">
          <Form.Item
            label={t('newPassword')}
            name="newPassword"
            rules={[
              { required: true, message: t('newPasswordRequired') },
              { min: 6, message: t('passwordMin') },
            ]}
          >
            <Input.Password placeholder={t('newPasswordRequired')} autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            label={t('confirmPassword')}
            name="confirmPassword"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: t('confirmPasswordRequired') },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error(t('passwordMismatch')));
                },
              }),
            ]}
          >
            <Input.Password placeholder={t('confirmPasswordRequired')} autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('deleteTitle')}
        open={deleteOpen}
        okText={t('confirmDelete')}
        cancelText={t('cancel')}
        okButtonProps={{ danger: true, disabled: deleteInput !== detail?.username }}
        confirmLoading={deleteMutation.isPending}
        onOk={() => deleteMutation.mutate()}
        onCancel={() => setDeleteOpen(false)}
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Alert type="warning" showIcon message={t('deleteWarning')} />
          <Paragraph style={{ marginBottom: 0 }}>{t('deleteRule1')}</Paragraph>
          <Paragraph style={{ marginBottom: 0 }}>{t('deleteRule2')}</Paragraph>
          <Text>{t('deleteConfirmPrompt', { username: detail?.username ?? EMPTY_TEXT })}</Text>
          <Input
            aria-label={t('usernamePlaceholder')}
            placeholder={t('usernamePlaceholder')}
            value={deleteInput}
            onChange={(event) => setDeleteInput(event.target.value)}
          />
        </Space>
      </Modal>

      <Modal
        title={t('restoreTitle')}
        open={restoreOpen}
        okText={t('confirmRestore')}
        cancelText={t('cancel')}
        confirmLoading={restoreMutation.isPending}
        onOk={() => restoreMutation.mutate()}
        onCancel={() => { setRestoreOpen(false); setRestoreError(null); }}
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Paragraph style={{ marginBottom: 0 }}>{t('restoreConfirm', { username: detail?.username ?? EMPTY_TEXT })}</Paragraph>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>{t('restoreHint')}</Paragraph>
          {restoreError ? <Alert type="error" showIcon message={restoreError} /> : null}
        </Space>
      </Modal>
    </Drawer>
  );
}

interface AddWorkspaceFormProps {
  userId: number;
  existingIds: number[];
  onAdded: (list: UserAdminWorkspace[]) => void;
}

function AddWorkspaceForm({ userId, existingIds, onAdded }: AddWorkspaceFormProps) {
  const { t } = useTranslation('userManagement');
  const [keyword, setKeyword] = useState('');
  const [workspaceId, setWorkspaceId] = useState<number | undefined>();
  const [level, setLevel] = useState<WorkspaceAccessLevel>('READ_ONLY');

  const optionsQuery = useAllWorkspaces(keyword, 1, 8);
  const candidates = (optionsQuery.data?.list ?? []).filter((item) => !existingIds.includes(item.id));

  const addMutation = useMutation({
    mutationFn: () => addUserAdminWorkspace(userId, workspaceId as number, level),
    onSuccess: (list) => {
      message.success(i18n.t('userManagement:workspaceAdded'));
      onAdded(list);
      setWorkspaceId(undefined);
      setKeyword('');
      setLevel('READ_ONLY');
    },
    onError: (error: Error) => message.error(error.message || i18n.t('userManagement:workspaceAddFailed')),
  });

  return (
    <Space wrap>
      <Select<number>
        showSearch
        filterOption={false}
        aria-label={t('workspaceSelect')}
        placeholder={t('workspaceSearchPlaceholder')}
        style={{ width: 240 }}
        value={workspaceId}
        loading={optionsQuery.isFetching}
        searchValue={keyword}
        onSearch={setKeyword}
        onChange={setWorkspaceId}
        notFoundContent={null}
        options={candidates.map((item) => ({ value: item.id, label: item.name }))}
      />
      <Select<WorkspaceAccessLevel>
        aria-label={t('levelSelect')}
        style={{ width: 120 }}
        value={level}
        onChange={setLevel}
        options={levelOptions(t)}
      />
      <Button
        type="primary"
        loading={addMutation.isPending}
        disabled={workspaceId === undefined}
        onClick={() => {
          if (workspaceId !== undefined) addMutation.mutate();
        }}
      >
        {t('add')}
      </Button>
    </Space>
  );
}
