import { useState } from 'react';
import {
  Alert, Button, Card, Descriptions, Divider, Empty, Input, List, Modal, Pagination, Radio, Space,
  Tag, Typography, message,
} from 'antd';
import { CheckOutlined, CloseOutlined, EditOutlined } from '@ant-design/icons';
import { usePendingReviews, useReviewMemory, useUpdateMemory } from './hooks';
import type { Memory } from './api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { Paragraph, Text } = Typography;

const typeLabel: Record<string, string> = { FACT: '事实', RULE: '规则', PREFERENCE: '偏好' };
const scopeLabel: Record<string, string> = { ORG: '组织全局', SQUAD: '小队', AGENT: '员工' };
const statusLabel: Record<string, { color: string; text: string }> = {
  ADOPTED: { color: 'success', text: '已采纳' },
  PENDING: { color: 'processing', text: '待审核' },
  REJECTED: { color: 'error', text: '已驳回' },
};
const typeOptions = Object.entries(typeLabel).map(([value, label]) => ({ value, label }));
const reviewScopeOptions = [
  { value: 'AGENT', label: '员工' },
  { value: 'SQUAD', label: '小队' },
  { value: 'ORG', label: '组织全局' },
];

const PAGE_SIZE = 20;

export function MemoryReviewPage() {
  const runWithAccess = useAccessCommand();
  const [page, setPage] = useState(1);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [rejectModalOpen, setRejectModalOpen] = useState(false);
  const [currentMemory, setCurrentMemory] = useState<Memory | null>(null);
  const [editedContent, setEditedContent] = useState('');
  const [editedType, setEditedType] = useState<Memory['type']>('FACT');
  const [reviewScope, setReviewScope] = useState<Memory['scope']>('AGENT');
  const [reviewOwnerRef, setReviewOwnerRef] = useState('');
  const [rejectComment, setRejectComment] = useState('');
  const [pendingReviewId, setPendingReviewId] = useState<number | null>(null);
  const [feedback, setFeedback] = useState<{ message: string; description: string } | null>(null);

  const { data = [], isLoading } = usePendingReviews({ page, size: PAGE_SIZE });
  const reviewMutation = useReviewMemory();
  const updateMutation = useUpdateMemory();

  const handleApprove = async (record: Memory) => {
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

  const handleEditApprove = (record: Memory) => {
    runWithAccess('READ_WRITE', '编辑并采纳记忆', () => {
      setCurrentMemory(record);
      setEditedContent(record.contentMd);
      setEditedType(record.type);
      setReviewScope(record.scope);
      setReviewOwnerRef(record.ownerRef == null ? '' : String(record.ownerRef));
      setEditModalOpen(true);
    });
  };

  const handleSubmitEditApprove = async () => {
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

  const handleReject = (record: Memory) => {
    runWithAccess('READ_WRITE', '驳回记忆', () => {
      setCurrentMemory(record);
      setRejectComment('');
      setRejectModalOpen(true);
    });
  };

  const handleSubmitReject = async () => {
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

  // 仅在初次加载数据时显示整页 loading；审核/驳回等单卡片操作通过 per-item 状态反馈，
  // 避免 mutation 期间全屏 Spin 把所有卡片同时遮住。
  const listLoading = isLoading;
  const anyMutationPending = reviewMutation.isPending || updateMutation.isPending;
  // 后端 /api/memories/reviews 仅返回当前页数据，不返回总数；
  // 沿用 MemoryListPage 的启发式：当前页满 size 则认为可能还有下一页。
  const hasMore = data.length >= PAGE_SIZE;
  const total = hasMore ? page * PAGE_SIZE + 1 : (page - 1) * PAGE_SIZE + data.length;

  return (
    <Card
      title="记忆审核台"
      extra={feedback ? <Tag color="success">{feedback.message}</Tag> : undefined}
    >
      {feedback && (
        <Alert
          showIcon
          type="success"
          message={feedback.message}
          description={feedback.description}
          style={{ marginBottom: 16 }}
        />
      )}

      <List
        rowKey="id"
        grid={{ gutter: 16, xs: 1, sm: 2, md: 2, lg: 3, xl: 4, xxl: 4 }}
        dataSource={data}
        loading={listLoading}
        locale={{ emptyText: <Empty description="暂无待审核记忆" /> }}
        renderItem={(item: Memory) => {
          const isThisPending = pendingReviewId === item.id;
          const otherPending = pendingReviewId !== null && pendingReviewId !== item.id;
          return (
            <List.Item>
              <Card
                title={
                  <Space size={8} align="center" wrap>
                    <Text strong>{item.title || '无标题'}</Text>
                    <Tag color="blue">{typeLabel[item.type] || item.type}</Tag>
                    <Tag color={statusLabel[item.status]?.color}>
                      {statusLabel[item.status]?.text || item.status}
                    </Tag>
                  </Space>
                }
                actions={[
                  <Button
                    key="adopt"
                    type="primary"
                    size="small"
                    loading={isThisPending}
                    disabled={otherPending}
                    icon={<CheckOutlined />}
                    onClick={() => handleApprove(item)}
                  >
                    采纳
                  </Button>,
                  <Button
                    key="edit"
                    size="small"
                    disabled={anyMutationPending}
                    icon={<EditOutlined />}
                    onClick={() => handleEditApprove(item)}
                  >
                    编辑采纳
                  </Button>,
                  <Button
                    key="reject"
                    size="small"
                    danger
                    disabled={anyMutationPending}
                    icon={<CloseOutlined />}
                    onClick={() => handleReject(item)}
                  >
                    驳回
                  </Button>,
                ]}
              >
              <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 12 }}>
                {item.contentMd}
              </Paragraph>
              <Divider style={{ margin: '12px 0 8px' }} />
              <Descriptions column={1} size="small" colon={false}>
                <Descriptions.Item label="范围">
                  {scopeLabel[item.scope] || item.scope}
                </Descriptions.Item>
                <Descriptions.Item label="归属">{item.ownerRef ?? '-'}</Descriptions.Item>
                <Descriptions.Item label="来源">
                  {item.source ? <Tag>{item.source}</Tag> : '-'}
                </Descriptions.Item>
                <Descriptions.Item label="来源引用">
                  {item.sourceRef || '-'}
                </Descriptions.Item>
                <Descriptions.Item label="创建时间">
                  {item.gmtCreate ? new Date(item.gmtCreate).toLocaleString('zh-CN') : '-'}
                </Descriptions.Item>
              </Descriptions>
            </Card>
          </List.Item>
          );
        }}
      />

      <Divider style={{ margin: '16px 0' }} />
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 8,
        }}
      >
        <Text type="secondary">
          {hasMore ? '当前页已满，可能还有更多待审核记忆' : `共 ${data.length} 条待审核`}
        </Text>
        <Pagination
          current={page}
          pageSize={PAGE_SIZE}
          total={total}
          showSizeChanger={false}
          onChange={(p) => setPage(p)}
        />
      </div>

      <Modal
        title="编辑后采纳"
        open={editModalOpen}
        onCancel={() => setEditModalOpen(false)}
        onOk={handleSubmitEditApprove}
        okText="确认采纳"
        cancelText="取消"
        confirmLoading={reviewMutation.isPending || updateMutation.isPending}
      >
        <p style={{ marginBottom: 8, color: '#666' }}>修改记忆内容后确认采纳：</p>
        <div style={{ marginBottom: 8, color: '#666' }}>类型</div>
        <div style={{ marginBottom: 12 }}>
          <Tag color="blue">当前类型：{typeLabel[editedType]}</Tag>
        </div>
        <Radio.Group
          value={editedType}
          options={typeOptions}
          onChange={(event) => setEditedType(event.target.value as Memory['type'])}
          optionType="button"
          buttonStyle="solid"
        />
        <div style={{ marginTop: 16, marginBottom: 8, color: '#666' }}>采纳范围</div>
        <Radio.Group
          value={reviewScope}
          options={reviewScopeOptions}
          onChange={(event) => setReviewScope(event.target.value as Memory['scope'])}
          optionType="button"
          buttonStyle="solid"
        />
        {reviewScope !== 'ORG' && (
          <Input
            style={{ marginTop: 12, marginBottom: 12 }}
            value={reviewOwnerRef}
            onChange={(event) => setReviewOwnerRef(event.target.value)}
            placeholder={reviewScope === 'SQUAD' ? '小队 ID' : '数字员工 ID'}
            aria-label="记忆归属 ID"
          />
        )}
        <Input.TextArea
          rows={8}
          value={editedContent}
          onChange={(e) => setEditedContent(e.target.value)}
        />
      </Modal>

      <Modal
        title="驳回记忆"
        open={rejectModalOpen}
        onCancel={() => setRejectModalOpen(false)}
        onOk={handleSubmitReject}
        okText="确认驳回"
        cancelText="取消"
        confirmLoading={reviewMutation.isPending}
      >
        <p style={{ marginBottom: 8, color: '#666' }}>请输入驳回原因（可选）：</p>
        <Input.TextArea
          rows={4}
          value={rejectComment}
          onChange={(e) => setRejectComment(e.target.value)}
          placeholder="驳回原因..."
        />
      </Modal>
    </Card>
  );
}
