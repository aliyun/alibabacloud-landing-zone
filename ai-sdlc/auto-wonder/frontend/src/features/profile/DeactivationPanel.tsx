import { useState } from 'react';
import { Alert, Button, Card, Input, Modal, Space, Typography, message } from 'antd';
import { ExclamationCircleOutlined, StopOutlined, UndoOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@/shared/auth/store';
import {
  DEACTIVATION_STATUS_QUERY_KEY,
  getDeactivationStatus,
  initiateDeactivation,
  revokeDeactivation,
} from './profileApi';

const { Text, Paragraph } = Typography;

function getApiMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function formatCountdown(expiresAt: string): string {
  const diff = new Date(expiresAt).getTime() - Date.now();
  if (diff <= 0) return '已到期';
  const days = Math.floor(diff / (1000 * 60 * 60 * 24));
  const hours = Math.floor((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
  if (days > 0) return `${days} 天 ${hours} 小时`;
  const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));
  return `${hours} 小时 ${minutes} 分钟`;
}

export function DeactivationPanel() {
  const queryClient = useQueryClient();
  const [confirmInput, setConfirmInput] = useState('');
  const [modalOpen, setModalOpen] = useState(false);

  const statusQuery = useQuery({
    queryKey: DEACTIVATION_STATUS_QUERY_KEY,
    queryFn: getDeactivationStatus,
    retry: false,
  });

  const status = statusQuery.data;
  const username = useAuthStore((s) => s.user?.username ?? '');

  const initiateMutation = useMutation({
    mutationFn: () => initiateDeactivation(confirmInput),
    onSuccess: async () => {
      message.success('注销申请已提交，冷静期为 7 天');
      setModalOpen(false);
      setConfirmInput('');
      await queryClient.invalidateQueries({ queryKey: DEACTIVATION_STATUS_QUERY_KEY });
    },
    onError: (error) => {
      message.error(getApiMessage(error, '注销申请提交失败'));
    },
  });

  const revokeMutation = useMutation({
    mutationFn: revokeDeactivation,
    onSuccess: async () => {
      message.success('注销申请已撤销，账号恢复正常');
      await queryClient.invalidateQueries({ queryKey: DEACTIVATION_STATUS_QUERY_KEY });
    },
    onError: (error) => {
      message.error(getApiMessage(error, '撤销操作失败'));
    },
  });

  if (statusQuery.isLoading) {
    return <Card title="账号注销" loading />;
  }

  if (statusQuery.error) {
    return (
      <Card title="账号注销">
        <Alert
          type="error"
          showIcon
          message="加载注销状态失败"
          description={getApiMessage(statusQuery.error, '请稍后重试')}
        />
      </Card>
    );
  }

  if (status?.pending) {
    return (
      <Card title="账号注销">
        <Alert
          type="warning"
          showIcon
          icon={<ExclamationCircleOutlined />}
          message="注销申请已提交，当前处于冷静期"
          description={
            <Space direction="vertical" style={{ marginTop: 8 }}>
              <Text>
                冷静期剩余：<Text strong>{status.coolingOffExpiresAt ? formatCountdown(status.coolingOffExpiresAt) : '计算中'}</Text>
              </Text>
              <Text type="secondary">
                冷静期结束后账号将被注销，个人数据将被匿名化处理。在此之前您可以随时撤销。
              </Text>
            </Space>
          }
        />
        <div style={{ marginTop: 16, textAlign: 'right' }}>
          <Button
            icon={<UndoOutlined />}
            onClick={() => revokeMutation.mutate()}
            loading={revokeMutation.isPending}
          >
            撤销注销申请
          </Button>
        </div>
      </Card>
    );
  }

  return (
    <Card title="账号注销">
      <Paragraph>
        注销后，您的账号将无法登录。个人数据（昵称、头像、手机号、邮箱等）将被删除或匿名化处理。
        您创建的历史工单和评论将被保留，但显示为"已注销用户"。
      </Paragraph>
      <Alert
        type="info"
        showIcon
        message="注销设有 7 天冷静期"
        description="提交注销申请后有 7 天冷静期，期间您可以随时撤销注销。冷静期结束后注销将正式生效。"
      />
      <div style={{ marginTop: 16, textAlign: 'right' }}>
        <Button
          danger
          icon={<StopOutlined />}
          onClick={() => setModalOpen(true)}
        >
          申请注销账号
        </Button>
      </div>

      <Modal
        title="确认注销账号"
        open={modalOpen}
        onCancel={() => { setModalOpen(false); setConfirmInput(''); }}
        footer={null}
        destroyOnClose
      >
        <Alert
          type="warning"
          showIcon
          message="此操作将启动账号注销流程"
          description={
            <Space direction="vertical" size={4}>
              <Text>1. 注销前有未完结工单将被阻止</Text>
              <Text>2. 冷静期为 7 天，期间可撤销</Text>
              <Text>3. 冷静期结束后账号不可登录，个人数据匿名化</Text>
            </Space>
          }
          style={{ marginBottom: 16 }}
        />
        <Text>请输入您的用户名 <Text strong>{username || '(无法获取)'}</Text> 以确认注销：</Text>
        <Input
          style={{ marginTop: 8 }}
          placeholder="输入用户名"
          value={confirmInput}
          onChange={(e) => setConfirmInput(e.target.value)}
          onPressEnter={() => {
            if (confirmInput === username) initiateMutation.mutate();
          }}
        />
        <div style={{ marginTop: 16, textAlign: 'right' }}>
          <Space>
            <Button onClick={() => { setModalOpen(false); setConfirmInput(''); }}>
              取消
            </Button>
            <Button
              danger
              type="primary"
              disabled={confirmInput !== username || !username}
              loading={initiateMutation.isPending}
              onClick={() => initiateMutation.mutate()}
            >
              确认注销
            </Button>
          </Space>
        </div>
      </Modal>
    </Card>
  );
}
