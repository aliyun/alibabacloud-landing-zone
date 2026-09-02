import { useEffect, useState } from 'react';
import { Modal, Radio, Space, Typography, message } from 'antd';
import { ApiError } from '@/shared/types/common';
import type { WorkspaceAccessLevel, WorkspaceListItem } from '@/shared/types/common';
import { useSubmitAccessRequest } from './workspaceDiscoveryApi';

const { Text } = Typography;

const LEVEL_OPTIONS: Array<{
  value: WorkspaceAccessLevel;
  label: string;
  description: string;
}> = [
  { value: 'READ_ONLY', label: '只读', description: '仅查看工单、Agent 与执行记录，不能修改任何内容' },
  { value: 'READ_WRITE', label: '读写', description: '可创建和编辑工单、Agent、技能等工作空间内容' },
  { value: 'ADMIN', label: '管理员', description: '可管理成员、访问级别与工作空间配置' },
];

interface AccessRequestModalProps {
  workspace: WorkspaceListItem | null;
  onClose: () => void;
}

export function AccessRequestModal({ workspace, onClose }: AccessRequestModalProps) {
  const [requestedLevel, setRequestedLevel] = useState<WorkspaceAccessLevel>('READ_ONLY');
  const { mutateAsync, isPending } = useSubmitAccessRequest();

  // Keyed on the workspace id rather than on open/close alone: the component instance
  // survives between openings, so a plain useState would silently carry the previous
  // choice into the next workspace's submission. Depending on the `workspace` object
  // instead of its id would reset the user's in-progress choice on any re-render that
  // hands back a new object identity (e.g. a list refetch), which is a subtler form of
  // the same bug.
  useEffect(() => {
    if (workspace) {
      setRequestedLevel('READ_ONLY');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspace?.id]);

  const handleSubmit = async () => {
    if (!workspace) return;
    try {
      await mutateAsync({ workspaceId: workspace.id, requestedLevel });
      message.success('申请已提交，请等待管理员审批');
      onClose();
    } catch (e) {
      // The mutation hook deliberately carries no onError, so the specific business
      // message (already pending, already a member, invalid level) is surfaced here.
      message.error(e instanceof ApiError ? e.message : '申请提交失败，请稍后重试');
    }
  };

  return (
    <Modal
      title={workspace ? `申请加入「${workspace.name}」` : ''}
      open={workspace !== null}
      okText="提交申请"
      cancelText="取消"
      confirmLoading={isPending}
      onCancel={onClose}
      onOk={handleSubmit}
      destroyOnHidden
    >
      <Text style={{ display: 'block', marginBottom: 12, color: '#697386' }}>
        选择需要的权限级别，提交后由工作空间管理员审批。
      </Text>
      <Radio.Group
        value={requestedLevel}
        onChange={(event) => setRequestedLevel(event.target.value as WorkspaceAccessLevel)}
      >
        <Space direction="vertical" size={12}>
          {LEVEL_OPTIONS.map((option) => (
            <Radio key={option.value} value={option.value}>
              <span style={{ fontWeight: 600, color: '#111827' }}>{option.label}</span>
              <Text style={{ display: 'block', color: '#697386', fontSize: 12 }}>
                {option.description}
              </Text>
            </Radio>
          ))}
        </Space>
      </Radio.Group>
    </Modal>
  );
}
