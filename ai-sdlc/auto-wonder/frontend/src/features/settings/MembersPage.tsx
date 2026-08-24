import { useState, type ReactNode } from 'react';
import {
  Button,
  Card,
  Popconfirm,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { ACCESS_LEVEL_LABEL } from '@/shared/auth/access';
import type { WorkspaceAccessLevel } from '@/shared/types/common';
import {
  useAddMember,
  useCurrentMembership,
  useMemberCandidates,
  useMembers,
  useRemoveMember,
  useTransferOwner,
  useUpdateMemberAccess,
  useUpdateMemberIdentityTags,
} from './hooks';
import { MemberAccessModal } from './MemberAccessModal';
import { OwnerTransferModal } from './OwnerTransferModal';
import type { MemberVO } from './api';

const ACCESS_LEVEL_COLORS: Record<WorkspaceAccessLevel, string> = {
  READ_ONLY: 'default',
  READ_WRITE: 'blue',
  ADMIN: 'green',
};

export function MembersPage() {
  const accessCommand = useAccessCommand();
  const { data: members = [], isLoading } = useMembers();
  const { data: membership } = useCurrentMembership();
  const isAdmin = membership?.accessLevel === 'ADMIN';
  const [candidateKeyword, setCandidateKeyword] = useState('');
  const [selectedUserId, setSelectedUserId] = useState<number>();
  const { data: candidates = [], isFetching: candidatesLoading } =
    useMemberCandidates(candidateKeyword);
  const addMemberMutation = useAddMember();
  const removeMemberMutation = useRemoveMember();
  const updateAccessMutation = useUpdateMemberAccess();
  const updateTagsMutation = useUpdateMemberIdentityTags();
  const transferOwnerMutation = useTransferOwner();

  const [levelFilter, setLevelFilter] = useState<WorkspaceAccessLevel | 'ALL'>('ALL');
  const [editTarget, setEditTarget] = useState<MemberVO | null>(null);
  const [removeConfirmUserId, setRemoveConfirmUserId] = useState<number>();
  const [ownerTransferOpen, setOwnerTransferOpen] = useState(false);

  const adminOnlyTip = (control: ReactNode) => {
    if (isAdmin) {
      return <>{control}</>;
    }
    return (
      <Tooltip title="仅管理员可操作">
        {/* antd Tooltip 对 disabled 控件不触发鼠标事件，须用 span 包裹 */}
        <span>{control}</span>
      </Tooltip>
    );
  };

  const filteredMembers = levelFilter === 'ALL'
    ? members
    : members.filter((member) => member.accessLevel === levelFilter);

  const handleAddMember = () => {
    if (selectedUserId === undefined) return;
    accessCommand('ADMIN', '添加成员', () => {
      addMemberMutation.mutate(selectedUserId, {
        onSuccess: () => {
          setSelectedUserId(undefined);
          setCandidateKeyword('');
        },
      });
    });
  };

  const handleEditConfirm = async (values: {
    accessLevel: WorkspaceAccessLevel;
    identityTags: string[];
  }) => {
    if (!editTarget) return;
    await accessCommand('ADMIN', '编辑成员', async () => {
      const tagsChanged =
        JSON.stringify(values.identityTags) !== JSON.stringify(editTarget.identityTags);
      try {
        if (values.accessLevel !== editTarget.accessLevel) {
          await updateAccessMutation.mutateAsync({
            userId: editTarget.userId,
            accessLevel: values.accessLevel,
          });
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
    await accessCommand('ADMIN', '移交 Owner', async () => {
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
      title: '用户',
      key: 'user',
      render: (_, member) => (
        <div>
          <Space size={6}>
            <span style={{ fontWeight: 500 }}>{member.nickname || member.username}</span>
            {member.owner && <Tag color="gold">工作空间所有者</Tag>}
          </Space>
          <div style={{ fontSize: 12, color: '#666' }}>{member.email}</div>
        </div>
      ),
    },
    {
      title: '访问等级',
      dataIndex: 'accessLevel',
      render: (level: WorkspaceAccessLevel) => (
        <Tag color={ACCESS_LEVEL_COLORS[level]}>{ACCESS_LEVEL_LABEL[level]}</Tag>
      ),
    },
    {
      title: '身份标签',
      dataIndex: 'identityTags',
      render: (tags: string[]) => (
        <Space size={4} wrap>
          {tags.length
            ? tags.map((tag) => <Tag key={tag}>{tag}</Tag>)
            : <span style={{ color: '#999' }}>-</span>}
        </Space>
      ),
    },
    {
      title: '加入时间',
      dataIndex: 'joinedAt',
      render: (value: string) => value ? new Date(value).toLocaleDateString() : '-',
    },
    {
      title: '操作',
      key: 'action',
      render: (_, member) => (
        <Space>
          {adminOnlyTip(
            <Button
              type="link"
              size="small"
              disabled={!isAdmin}
              onClick={() => accessCommand('ADMIN', '编辑成员', () => setEditTarget(member))}
            >
              编辑
            </Button>,
          )}
          {adminOnlyTip(
            <Popconfirm
              title="确定移除该成员？"
              open={removeConfirmUserId === member.userId}
              onOpenChange={(open) => {
                if (!open) {
                  setRemoveConfirmUserId(undefined);
                  return;
                }
                accessCommand('ADMIN', '移除成员', () => setRemoveConfirmUserId(member.userId));
              }}
              onConfirm={() => accessCommand('ADMIN', '移除成员', () =>
                removeMemberMutation.mutate(member.userId, {
                  onSettled: () => setRemoveConfirmUserId(undefined),
                }))}
            >
              <Button type="link" size="small" danger disabled={!isAdmin}>移除</Button>
            </Popconfirm>,
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card
      title="成员管理"
      extra={adminOnlyTip(
        <Button
          disabled={!isAdmin}
          onClick={() =>
            accessCommand('ADMIN', '移交 Owner', () => setOwnerTransferOpen(true))}
        >
          移交 Owner
        </Button>,
      )}
    >
      <Space
        style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}
        align="start"
        wrap
      >
        <Segmented
          options={[
            { label: `全部 (${members.length})`, value: 'ALL' },
            { label: '管理员', value: 'ADMIN' },
            { label: '读写', value: 'READ_WRITE' },
            { label: '只读', value: 'READ_ONLY' },
          ]}
          value={levelFilter}
          onChange={(value) => setLevelFilter(value as WorkspaceAccessLevel | 'ALL')}
        />
        <Space.Compact>
          {adminOnlyTip(
            <Select
              showSearch
              allowClear
              filterOption={false}
              aria-label="搜索全局人员"
              disabled={!isAdmin}
              value={selectedUserId}
              placeholder="搜索全局人员"
              loading={candidatesLoading}
              style={{ width: 280 }}
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
                candidateKeyword.trim() ? '暂无可添加人员' : '输入姓名、用户名或邮箱搜索'
              }
            />,
          )}
          {adminOnlyTip(
            <Button
              type="primary"
              disabled={!isAdmin || selectedUserId === undefined}
              loading={addMemberMutation.isPending}
              onClick={handleAddMember}
            >
              添加成员
            </Button>,
          )}
        </Space.Compact>
      </Space>
      <Table
        rowKey="userId"
        columns={columns}
        dataSource={filteredMembers}
        loading={isLoading}
        pagination={false}
        scroll={{ x: 880 }}
      />
      <MemberAccessModal
        open={editTarget !== null}
        member={editTarget}
        loading={updateAccessMutation.isPending || updateTagsMutation.isPending}
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
