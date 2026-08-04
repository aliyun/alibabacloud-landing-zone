import { Input, Modal, Radio, Tag } from 'antd';
import type { Memory } from './api';

const typeLabel: Record<string, string> = { FACT: '事实', RULE: '规则', PREFERENCE: '偏好' };
const typeOptions = Object.entries(typeLabel).map(([value, label]) => ({ value, label }));
const reviewScopeOptions = [
  { value: 'AGENT', label: '员工' },
  { value: 'SQUAD', label: '小队' },
  { value: 'ORG', label: '组织全局' },
];

interface MemoryReviewModalsProps {
  editModalOpen: boolean;
  rejectModalOpen: boolean;
  editedContent: string;
  editedType: Memory['type'];
  reviewScope: Memory['scope'];
  reviewOwnerRef: string;
  rejectComment: string;
  reviewPending: boolean;
  updatePending: boolean;
  onEditModalClose: () => void;
  onRejectModalClose: () => void;
  onEditedContentChange: (value: string) => void;
  onEditedTypeChange: (value: Memory['type']) => void;
  onReviewScopeChange: (value: Memory['scope']) => void;
  onReviewOwnerRefChange: (value: string) => void;
  onRejectCommentChange: (value: string) => void;
  onSubmitEditApprove: () => void;
  onSubmitReject: () => void;
}

export function MemoryReviewModals({
  editModalOpen,
  rejectModalOpen,
  editedContent,
  editedType,
  reviewScope,
  reviewOwnerRef,
  rejectComment,
  reviewPending,
  updatePending,
  onEditModalClose,
  onRejectModalClose,
  onEditedContentChange,
  onEditedTypeChange,
  onReviewScopeChange,
  onReviewOwnerRefChange,
  onRejectCommentChange,
  onSubmitEditApprove,
  onSubmitReject,
}: MemoryReviewModalsProps) {
  return (
    <>
      <Modal
        title="编辑后采纳"
        open={editModalOpen}
        onCancel={onEditModalClose}
        onOk={onSubmitEditApprove}
        okText="确认采纳"
        cancelText="取消"
        confirmLoading={reviewPending || updatePending}
      >
        <p style={{ marginBottom: 8, color: '#666' }}>修改记忆内容后确认采纳：</p>
        <div style={{ marginBottom: 8, color: '#666' }}>类型</div>
        <div style={{ marginBottom: 12 }}>
          <Tag color="blue">当前类型：{typeLabel[editedType]}</Tag>
        </div>
        <Radio.Group
          value={editedType}
          options={typeOptions}
          onChange={(event) => onEditedTypeChange(event.target.value as Memory['type'])}
          optionType="button"
          buttonStyle="solid"
        />
        <div style={{ marginTop: 16, marginBottom: 8, color: '#666' }}>采纳范围</div>
        <Radio.Group
          value={reviewScope}
          options={reviewScopeOptions}
          onChange={(event) => onReviewScopeChange(event.target.value as Memory['scope'])}
          optionType="button"
          buttonStyle="solid"
        />
        {reviewScope !== 'ORG' && (
          <Input
            style={{ marginTop: 12, marginBottom: 12 }}
            value={reviewOwnerRef}
            onChange={(event) => onReviewOwnerRefChange(event.target.value)}
            placeholder={reviewScope === 'SQUAD' ? '小队 ID' : '数字员工 ID'}
            aria-label="记忆归属 ID"
          />
        )}
        <Input.TextArea
          rows={8}
          value={editedContent}
          onChange={(e) => onEditedContentChange(e.target.value)}
        />
      </Modal>

      <Modal
        title="驳回记忆"
        open={rejectModalOpen}
        onCancel={onRejectModalClose}
        onOk={onSubmitReject}
        okText="确认驳回"
        cancelText="取消"
        confirmLoading={reviewPending}
      >
        <p style={{ marginBottom: 8, color: '#666' }}>请输入驳回原因（可选）：</p>
        <Input.TextArea
          rows={4}
          value={rejectComment}
          onChange={(e) => onRejectCommentChange(e.target.value)}
          placeholder="驳回原因..."
        />
      </Modal>
    </>
  );
}
