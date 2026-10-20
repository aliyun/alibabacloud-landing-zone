import { useTranslation } from 'react-i18next';
import { formatDateTime, formatNumber } from '@/shared/i18n/format';
import { PageHeading } from '@/shared/ui/PageHeading';
import { useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Button,
  Card,
  Popconfirm,
  Select,
  Space,
  Tabs,
  Tag,
  Tooltip} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { Table } from '@/shared/theme/ThemedTable';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import {
  useAddMember,
  useCurrentMembership,
  useMemberCandidates,
  useMembers,
  useRemoveMember,
  useTransferOwner,
  useUpdateMemberRole,
  useWorkspaceRoles,
  useUpdateMemberIdentityTags,
} from './hooks';
import { AccessRequestsPanel } from './AccessRequestsPanel';
import { workspaceRoleLabel } from './roles';
import { WorkspaceRolesPanel } from './WorkspaceRolesPanel';
import { MemberAccessModal } from './MemberAccessModal';
import { OwnerTransferModal } from './OwnerTransferModal';
import type { MemberVO } from './api';

export const DEFAULT_MEMBERS_TAB = 'members';
const MEMBERS_TAB_KEYS: readonly string[] = [DEFAULT_MEMBERS_TAB, 'roles', 'requests'];

/** Whitelisted so an unknown `tab` value keeps the historical default instead of blanking the pane. */
export function resolveMembersTab(tabParam: string | null): string {
  return tabParam !== null && MEMBERS_TAB_KEYS.includes(tabParam)
    ? tabParam
    : DEFAULT_MEMBERS_TAB;
}

export function MembersPage() {
  useTranslation('shared');
  const { t } = useTranslation('settings');
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = resolveMembersTab(searchParams.get('tab'));
  const accessCommand = useAccessCommand();
  const { data: members = [], isLoading } = useMembers();
  const { data: roles = [] } = useWorkspaceRoles();
  const [selectedRoleId, setSelectedRoleId] = useState<number>();
  const { data: membership } = useCurrentMembership();
  const isAdmin = membership?.accessLevel === 'ADMIN';
  const [candidateKeyword, setCandidateKeyword] = useState('');
  const [selectedUserId, setSelectedUserId] = useState<number>();
  const { data: candidates = [], isFetching: candidatesLoading } =
    useMemberCandidates(candidateKeyword);
  const addMemberMutation = useAddMember();
  const removeMemberMutation = useRemoveMember();
  const updateRoleMutation = useUpdateMemberRole();
  const updateTagsMutation = useUpdateMemberIdentityTags();
  const transferOwnerMutation = useTransferOwner();

  const [roleFilter, setRoleFilter] = useState<number | 'ALL'>('ALL');
  const [memberPage, setMemberPage] = useState(1);
  const [memberPageSize, setMemberPageSize] = usePageSizePreference(
    'autowonder.members.pageSize', [10, 20, 50, 100], 10);
  const [editTarget, setEditTarget] = useState<MemberVO | null>(null);
  const [removeConfirmUserId, setRemoveConfirmUserId] = useState<number>();
  const [ownerTransferOpen, setOwnerTransferOpen] = useState(false);

  const adminOnlyTip = (control: ReactNode) => {
    if (isAdmin) {
      return <>{control}</>;
    }
    return (
      <Tooltip title={t('adminOnly')}>
        {/* antd Tooltip 对 disabled 控件不触发鼠标事件，须用 span 包裹 */}
        <span>{control}</span>
      </Tooltip>
    );
  };

  const filteredMembers = roleFilter === 'ALL'
    ? members
    : members.filter((member) => member.roleId === roleFilter);

  const handleAddMember = () => {
    if (selectedUserId === undefined) return;
    accessCommand('ADMIN', t('addMember'), () => {
      addMemberMutation.mutate({ userId: selectedUserId, roleId: selectedRoleId }, {
        onSuccess: () => {
          setSelectedUserId(undefined);
          setCandidateKeyword('');
        },
      });
    });
  };

  const handleEditConfirm = async (values: {
    roleId: number;
    identityTags: string[];
  }) => {
    if (!editTarget) return;
    await accessCommand('ADMIN', t('editMember'), async () => {
      const tagsChanged =
        JSON.stringify(values.identityTags) !== JSON.stringify(editTarget.identityTags);
      try {
        if (values.roleId !== editTarget.roleId) {
          await updateRoleMutation.mutateAsync({ userId: editTarget.userId, roleId: values.roleId });
        }
        if (tagsChanged) {
          await updateTagsMutation.mutateAsync({
            userId: editTarget.userId,
            identityTags: values.identityTags,
          });
        }
        setEditTarget(null);
      } catch {
        // Mutation hooks preserve the backend error message and keep the editor open.
      }
    });
  };

  const handleTransferOwner = async (targetUserId: number) => {
    await accessCommand('ADMIN', t('transferOwner'), async () => {
      try {
        await transferOwnerMutation.mutateAsync(targetUserId);
        setOwnerTransferOpen(false);
      } catch {
        // Mutation hook reports the backend error and keeps the explicit selection.
      }
    });
  };

  const columns: ColumnsType<MemberVO> = [
    {
      title: t('user'),
      key: 'user',
      render: (_, member) => (
        <div>
          <Space size={6}>
            <span style={{ fontWeight: 500 }}>{member.nickname || member.username}</span>
            {member.owner && <Tag color="gold">{t('workspaceOwner')}</Tag>}
          </Space>
          <div style={{ fontSize: 12, color: 'var(--aw-muted)' }}>{member.email}</div>
        </div>
      ),
    },
    {
      title: t('role'),
      key: 'role',
      render: (_, member) => <Tag color={member.accessLevel === 'ADMIN' ? 'green' : undefined}>
        {workspaceRoleLabel({ id: member.roleId, code: member.roleCode, name: member.roleName })}
      </Tag>,
    },
    {
      title: t('tags'),
      dataIndex: 'identityTags',
      render: (tags: string[]) => (
        <Space size={4} wrap>
          {tags.length
            ? tags.map((tag) => <Tag key={tag}>{tag}</Tag>)
            : <span style={{ color: 'var(--aw-muted)' }}>-</span>}
        </Space>
      ),
    },
    {
      title: t('joinedAt'),
      dataIndex: 'joinedAt',
      render: (value: string) => value ? formatDateTime(value, { year: 'numeric', month: 'numeric', day: 'numeric' }) : '-',
    },
    {
      title: t('actions'),
      key: 'action',
      render: (_, member) => (
        <Space>
          {adminOnlyTip(
            <Button
              type="link"
              size="small"
              disabled={!isAdmin}
              onClick={() => accessCommand('ADMIN', t('editMember'), () => setEditTarget(member))}
            >
              {t('edit')}
            </Button>,
          )}
          {adminOnlyTip(
            <Popconfirm
              title={t('removeConfirm')}
              open={removeConfirmUserId === member.userId}
              onOpenChange={(open) => {
                if (!open) {
                  setRemoveConfirmUserId(undefined);
                  return;
                }
                accessCommand('ADMIN', t('removeMember'), () => setRemoveConfirmUserId(member.userId));
              }}
              onConfirm={() => accessCommand('ADMIN', t('removeMember'), () =>
                removeMemberMutation.mutate(member.userId, {
                  onSettled: () => setRemoveConfirmUserId(undefined),
                }))}
            >
              <Button type="link" size="small" danger disabled={!isAdmin}>{t('remove')}</Button>
            </Popconfirm>,
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card className="aw-content-card" title={<PageHeading title={t('members')} />}
      extra={
        <Space size={12}>
          {adminOnlyTip(
            <Button
              disabled={!isAdmin}
              onClick={() =>
                accessCommand('ADMIN', t('transferOwner'), () => setOwnerTransferOpen(true))}
            >
              {t('transferOwner')}
            </Button>,
          )}
          {activeTab === 'members' && (
            <Space size={8}>
              {adminOnlyTip(
                <Select
                  showSearch
                  allowClear
                  filterOption={false}
                  aria-label={t('searchPeople')}
                  disabled={!isAdmin}
                  value={selectedUserId}
                  placeholder={t('searchPeople')}
                  loading={candidatesLoading}
                  style={{ width: 240 }}
                  onSearch={setCandidateKeyword}
                  onClear={() => {
                    setSelectedUserId(undefined);
                    setCandidateKeyword('');
                  }}
                  onChange={setSelectedUserId}
                  options={candidates.map((candidate) => ({
                    value: candidate.userId,
                    label: `${candidate.nickname || candidate.username}${candidate.email ? ` (${candidate.email})` : ''}`,
                  }))}
                  notFoundContent={
                    candidateKeyword.trim() ? t('noCandidates') : t('searchPeopleHint')
                  }
                />,
              )}
              <Select aria-label={t('newMemberRole')} disabled={!isAdmin} style={{ minWidth: 140 }}
                value={selectedRoleId ?? roles.find((role) => role.code === 'READ_ONLY')?.id}
                onChange={setSelectedRoleId} options={roles.map((role) => ({ value: role.id, label: workspaceRoleLabel(role) }))} />
              {adminOnlyTip(
                <Button
                  type="primary"
                  icon={<PlusOutlined aria-hidden="true" />}
                  aria-label={t('addMember')}
                  disabled={!isAdmin || selectedUserId === undefined}
                  loading={addMemberMutation.isPending}
                  onClick={handleAddMember}
                >
                  {t('addMember')}
                </Button>,
              )}
            </Space>
          )}
        </Space>
      }
    >
      <Tabs
        activeKey={activeTab}
        onChange={(key) => {
          const next = new URLSearchParams(searchParams);
          if (key === DEFAULT_MEMBERS_TAB) {
            next.delete('tab');
          } else {
            next.set('tab', key);
          }
          setSearchParams(next, { replace: true });
        }}
        items={[
          {
            key: 'members',
            label: t('members'),
            children: (
              <>
                <div style={{ marginBottom: 16 }}>
                  <Select
                    aria-label={t('filterRole')} style={{ minWidth: 180 }}
                    options={[
                      { label: t('allMembers', { count: members.length, total: formatNumber(members.length) }), value: 'ALL' as const },
                      ...roles.map((role) => ({ label: workspaceRoleLabel(role), value: role.id })),
                    ]}
                    value={roleFilter}
                    onChange={(value) => { setRoleFilter(value); setMemberPage(1); }}
                  />
                </div>
                <Table
                  rowKey="userId"
                  columns={columns}
                  dataSource={filteredMembers}
                  loading={isLoading}
                  pagination={{
                    current: memberPage,
                    pageSize: memberPageSize,
                    total: filteredMembers.length,
                    onChange: (nextPage, nextSize) => {
                      setMemberPage(nextPage);
                      if (nextSize !== memberPageSize) setMemberPageSize(nextSize);
                    },
                    showSizeChanger: true,
                    showTotal: (total) => t('totalMembers', { count: total, total: formatNumber(total) }),
                  }}
                  scroll={{ x: 880 }}
                />
              </>
            ),
          },
          { key: 'roles', label: t('roles'), children: <WorkspaceRolesPanel isAdmin={isAdmin} /> },
          {
            key: 'requests',
            label: t('accessRequests'),
            children: <AccessRequestsPanel />,
          },
        ]}
      />
      <MemberAccessModal
        open={editTarget !== null}
        member={editTarget}
        roles={roles}
        loading={updateRoleMutation.isPending || updateTagsMutation.isPending}
        onClose={() => setEditTarget(null)}
        onConfirm={handleEditConfirm}
      />
      <OwnerTransferModal
        open={ownerTransferOpen}
        candidates={members.filter((member) => !member.owner)}
        loading={transferOwnerMutation.isPending}
        onClose={() => setOwnerTransferOpen(false)}
        onConfirm={handleTransferOwner}
      />
    </Card>
  );
}
