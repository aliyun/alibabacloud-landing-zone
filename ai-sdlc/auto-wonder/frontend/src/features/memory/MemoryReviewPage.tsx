import { useState } from 'react';
import {
  Alert, Button, Card, Descriptions, Divider, Empty, List, Pagination,
  Space, Tag, Typography,
} from 'antd';
import { CheckOutlined, CloseOutlined, EditOutlined } from '@ant-design/icons';
import { usePendingReviews } from './hooks';
import type { Memory } from './api';
import { useMemoryReviewActions } from './useMemoryReviewActions';
import { MemoryReviewModals } from './MemoryReviewModals';

const { Paragraph, Text } = Typography;

const typeLabel: Record<string, string> = { FACT: '事实', RULE: '规则', PREFERENCE: '偏好' };
const scopeLabel: Record<string, string> = { ORG: '组织全局', SQUAD: '小队', AGENT: '员工' };
const statusLabel: Record<string, { color: string; text: string }> = {
  ADOPTED: { color: 'success', text: '已采纳' },
  PENDING: { color: 'processing', text: '待审核' },
  REJECTED: { color: 'error', text: '已驳回' },
};

const PAGE_SIZE = 20;

export function MemoryReviewPage() {
  const [page, setPage] = useState(1);

  const { data = [], isLoading } = usePendingReviews({ page, size: PAGE_SIZE });
  const review = useMemoryReviewActions();

  const listLoading = isLoading;
  const anyMutationPending = review.reviewMutation.isPending || review.updateMutation.isPending;
  const hasMore = data.length >= PAGE_SIZE;
  const total = hasMore ? page * PAGE_SIZE + 1 : (page - 1) * PAGE_SIZE + data.length;

  return (
    <Card
      title="记忆审核台"
      extra={review.feedback ? <Tag color="success">{review.feedback.message}</Tag> : undefined}
    >
      {review.feedback && (
        <Alert
          showIcon
          type="success"
          message={review.feedback.message}
          description={review.feedback.description}
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
          const isThisPending = review.pendingReviewId === item.id;
          const otherPending = review.pendingReviewId !== null && review.pendingReviewId !== item.id;
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
                    onClick={() => review.approve(item)}
                  >
                    采纳
                  </Button>,
                  <Button
                    key="edit"
                    size="small"
                    disabled={anyMutationPending}
                    icon={<EditOutlined />}
                    onClick={() => review.openEditApprove(item)}
                  >
                    编辑采纳
                  </Button>,
                  <Button
                    key="reject"
                    size="small"
                    danger
                    disabled={anyMutationPending}
                    icon={<CloseOutlined />}
                    onClick={() => review.openReject(item)}
                  >
                    驳回
                  </Button>,
                ]}
              >
              <div style={{ maxHeight: 200, overflowY: 'auto' }}>
                <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 12 }}>
                  {item.contentMd}
                </Paragraph>
              </div>
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

      <MemoryReviewModals
        editModalOpen={review.editModalOpen}
        rejectModalOpen={review.rejectModalOpen}
        editedContent={review.editedContent}
        editedType={review.editedType}
        reviewScope={review.reviewScope}
        reviewOwnerRef={review.reviewOwnerRef}
        rejectComment={review.rejectComment}
        reviewPending={review.reviewMutation.isPending}
        updatePending={review.updateMutation.isPending}
        onEditModalClose={() => review.setEditModalOpen(false)}
        onRejectModalClose={() => review.setRejectModalOpen(false)}
        onEditedContentChange={review.setEditedContent}
        onEditedTypeChange={review.setEditedType}
        onReviewScopeChange={review.setReviewScope}
        onReviewOwnerRefChange={review.setReviewOwnerRef}
        onRejectCommentChange={review.setRejectComment}
        onSubmitEditApprove={review.submitEditApprove}
        onSubmitReject={review.submitReject}
      />
    </Card>
  );
}
