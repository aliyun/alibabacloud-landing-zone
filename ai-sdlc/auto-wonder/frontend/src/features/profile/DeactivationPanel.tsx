import { Trans, useTranslation } from 'react-i18next';
import { i18n } from '@/shared/i18n';
import { useState } from 'react';
import { Alert, Button, Card, Input, Modal, Space, Typography, message } from 'antd';
import { CloseOutlined, ExclamationCircleOutlined, StopOutlined } from '@ant-design/icons';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/shared/auth/store';
import { deleteMyAccount } from './profileApi';

const { Text, Paragraph } = Typography;

function getApiMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

export function DeactivationPanel() {
  const { t } = useTranslation('profile');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [confirmInput, setConfirmInput] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const username = useAuthStore((s) => s.user?.username ?? '');
  const clear = useAuthStore((s) => s.clear);

  const deleteMutation = useMutation({
    mutationFn: () => deleteMyAccount(confirmInput),
    onSuccess: () => {
      message.success(i18n.t('profile:deactivation.deleted'));
      setModalOpen(false);
      setConfirmInput('');
      clear();
      queryClient.clear();
      navigate('/login');
    },
    onError: (error) => {
      message.error(getApiMessage(error, i18n.t('profile:deactivation.deleteFailed')));
    },
  });

  return (
    <Card title={t('deactivation.title')}>
      <Paragraph>{t('deactivation.notice')}</Paragraph>
      <Alert
        type="warning"
        showIcon
        icon={<ExclamationCircleOutlined aria-hidden="true" />}
        message={t('deactivation.warning')}
        description={t('deactivation.warningHint')}
      />
      <div style={{ marginTop: 16, textAlign: 'right' }}>
        <Button aria-label={t('deactivation.request')}
          danger
          icon={<StopOutlined aria-hidden="true" />}
          onClick={() => setModalOpen(true)}
        >{t('deactivation.request')}</Button>
      </div>

      <Modal
        closable={{ closeIcon: <CloseOutlined aria-hidden="true" />, 'aria-label': t('close') }}
        title={t('deactivation.confirmTitle')}
        open={modalOpen}
        onCancel={() => { setModalOpen(false); setConfirmInput(''); }}
        footer={null}
        destroyOnClose
      >
        <Alert
          type="warning"
          showIcon
          message={t('deactivation.warning')}
          description={
            <Space direction="vertical" size={4}>
              <Text>{t('deactivation.rule1')}</Text>
              <Text>{t('deactivation.rule2')}</Text>
              <Text>{t('deactivation.rule3')}</Text>
            </Space>
          }
          style={{ marginBottom: 16 }}
        />
        <Text><Trans t={t} i18nKey="deactivation.confirmPrompt" components={{ identity: <Text strong>{username || t('deactivation.unavailable')}</Text> }} /></Text>
        <Input
          style={{ marginTop: 8 }}
          placeholder={t('deactivation.username')}
          aria-label={t('deactivation.username')}
          value={confirmInput}
          onChange={(e) => setConfirmInput(e.target.value)}
          onPressEnter={() => {
            if (username && confirmInput === username && !deleteMutation.isPending) deleteMutation.mutate();
          }}
        />
        <div style={{ marginTop: 16, textAlign: 'right' }}>
          <Space>
            <Button aria-label={t('cancel')} onClick={() => { setModalOpen(false); setConfirmInput(''); }}>{t('cancel')}</Button>
            <Button aria-label={t('deactivation.confirm')}
              danger
              type="primary"
              disabled={confirmInput !== username || !username}
              loading={deleteMutation.isPending}
              onClick={() => deleteMutation.mutate()}
            >{t('deactivation.confirm')}</Button>
          </Space>
        </div>
      </Modal>
    </Card>
  );
}
