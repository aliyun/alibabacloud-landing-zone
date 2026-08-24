import { useState } from 'react';
import { message } from 'antd';
import { useReviewMemory, useUpdateMemory } from './hooks';
import type { Memory } from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

export interface MemoryReviewActionsState {
  pendingReviewId: number | null;
  editModalOpen: boolean;
  rejectModalOpen: boolean;
  currentMemory: Memory | null;
  editedContent: string;
  editedType: Memory['type'];
  reviewScope: Memory['scope'];
  reviewOwnerRef: string;
  rejectComment: string;
  feedback: { message: string; description: string } | null;
}

export function useMemoryReviewActions() {
  const runWithAccess = useAccessCommand();
  const [pendingReviewId, setPendingReviewId] = useState<number | null>(null);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [rejectModalOpen, setRejectModalOpen] = useState(false);
  const [currentMemory, setCurrentMemory] = useState<Memory | null>(null);
  const [editedContent, setEditedContent] = useState('');
  const [editedType, setEditedType] = useState<Memory['type']>('FACT');
  const [reviewScope, setReviewScope] = useState<Memory['scope']>('AGENT');
  const [reviewOwnerRef, setReviewOwnerRef] = useState('');
  const [rejectComment, setRejectComment] = useState('');
  const [feedback, setFeedback] = useState<{ message: string; description: string } | null>(null);

  const reviewMutation = useReviewMemory();
  const updateMutation = useUpdateMemory();

  const approve = async (record: Memory) => {
    await runWithAccess('READ_WRITE', '采纳记忆', async () => {
      setPendingReviewId(record.id);
      try {
        await reviewMutation.mutateAsync({
          id: record.id,
          params: {
            decision: 'ADOPT',
            scope: record.scope,
            ownerRef: record.scope === 'ORG' ? undefined : record.ownerRef ?? undefined,
          },
        });
        message.success('已采纳');
      } catch (err: unknown) {
        if (err && typeof err === 'object' && 'message' in err) {
          message.error((err as Error).message);
        }
      } finally {
        setPendingReviewId(null);
      }
    });
  };

  const openEditApprove = (record: Memory) => {
    runWithAccess('READ_WRITE', '编辑并采纳记忆', () => {
      setCurrentMemory(record);
      setEditedContent(record.contentMd ?? '');
      setEditedType(record.type);
      setReviewScope(record.scope);
      setReviewOwnerRef(record.ownerRef == null ? '' : String(record.ownerRef));
      setEditModalOpen(true);
    });
  };

  const submitEditApprove = async () => {
    if (!currentMemory) return;
    await runWithAccess('READ_WRITE', '编辑并采纳记忆', async () => {
      const targetOwnerRef = reviewOwnerRef ? Number(reviewOwnerRef) : currentMemory.ownerRef ?? undefined;
      setPendingReviewId(currentMemory.id);
      try {
        await updateMutation.mutateAsync({
          id: currentMemory.id,
          params: {
            title: currentMemory.title ?? undefined,
            contentMd: editedContent,
            type: editedType,
          },
        });
        await reviewMutation.mutateAsync({
          id: currentMemory.id,
          params: {
            decision: 'ADOPT',
            scope: reviewScope,
            ownerRef: reviewScope === 'ORG' ? undefined : targetOwnerRef,
          },
        });
        message.success('编辑后采纳成功');
        setFeedback({
          message: '编辑后采纳成功',
          description: `${currentMemory.title || '该记忆'} 已按最新内容和类型采纳。`,
        });
        setEditModalOpen(false);
      } catch (err: unknown) {
        if (err && typeof err === 'object' && 'message' in err) {
          message.error((err as Error).message);
        }
      } finally {
        setPendingReviewId(null);
      }
    });
  };

  const openReject = (record: Memory) => {
    runWithAccess('READ_WRITE', '驳回记忆', () => {
      setCurrentMemory(record);
      setRejectComment('');
      setRejectModalOpen(true);
    });
  };

  const submitReject = async () => {
    if (!currentMemory) return;
    await runWithAccess('READ_WRITE', '驳回记忆', async () => {
      setPendingReviewId(currentMemory.id);
      try {
        await reviewMutation.mutateAsync({
          id: currentMemory.id,
          params: { decision: 'REJECT', comment: rejectComment || undefined },
        });
        message.success('已驳回');
        setRejectModalOpen(false);
      } catch (err: unknown) {
        if (err && typeof err === 'object' && 'message' in err) {
          message.error((err as Error).message);
        }
      } finally {
        setPendingReviewId(null);
      }
    });
  };

  return {
    approve,
    openEditApprove,
    submitEditApprove,
    openReject,
    submitReject,
    setEditModalOpen,
    setRejectModalOpen,
    setEditedContent,
    setEditedType,
    setReviewScope,
    setReviewOwnerRef,
    setRejectComment,
    setFeedback,
    pendingReviewId,
    editModalOpen,
    rejectModalOpen,
    currentMemory,
    editedContent,
    editedType,
    reviewScope,
    reviewOwnerRef,
    rejectComment,
    feedback,
    reviewMutation,
    updateMutation,
  };
}
