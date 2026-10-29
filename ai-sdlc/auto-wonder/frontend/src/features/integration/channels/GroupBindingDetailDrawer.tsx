import { useTranslation } from 'react-i18next';
import { Alert, Button, Descriptions, Drawer, Popconfirm, Space, Tag, Typography } from 'antd';

/** Channel-neutral view model so both channels render the same read-only detail surface. */
export interface GroupBindingDetailModel {
  bindingMode: 'SHARED' | 'LEGACY';
  groupId: string | null;
  agentLabel: string;
  robotIdentity: string | null;
  transportLabel: string | null;
  enabled: boolean;
  diagnostics: { label: string; value: string }[];
}

interface Props {
  open: boolean;
  model: GroupBindingDetailModel | null;
  onClose: () => void;
  onEdit: () => void;
  onDelete: () => void;
  deleteLoading?: boolean;
}

function diagnosticLine(item: { label: string; value: string }): string {
  return `${item.label}: ${item.value}`;
}

/**
 * Read-only details: every technical field lives here instead of the list. Secrets are never part
 * of the model, and deletion (the only destructive action) is guarded by a second confirmation.
 */
export function GroupBindingDetailDrawer({
  open,
  model,
  onClose,
  onEdit,
  onDelete,
  deleteLoading = false,
}: Props) {
  const { t } = useTranslation('integration');
  const shared = model?.bindingMode === 'SHARED';
  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={560}
      title={t('detailTitle')}
      closable={{ 'aria-label': t('close') }}
      extra={<Button onClick={() => onClose()}>{t('close')}</Button>}
    >
      {model && (
        <>
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label={t('detailBindingMode')}>
              <Tag color={shared ? 'blue' : 'default'}>
                {shared ? t('sharedMode') : t('legacyMode')}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('detailGroupId')}>
              {model.groupId ? (
                <Typography.Paragraph
                  copyable={{ text: model.groupId, tooltips: [t('copyGroupId'), t('copied')] }}
                  style={{ marginBottom: 0 }}
                >
                  <Typography.Text code style={{ wordBreak: 'break-all' }}>{model.groupId}</Typography.Text>
                </Typography.Paragraph>
              ) : (
                t('noGroupIdIdentity')
              )}
            </Descriptions.Item>
            <Descriptions.Item label={t('detailAgent')}>{model.agentLabel}</Descriptions.Item>
            <Descriptions.Item label={t('detailRobotIdentity')}>
              {model.robotIdentity ? <Typography.Text code>{model.robotIdentity}</Typography.Text> : t('notAvailable')}
            </Descriptions.Item>
            <Descriptions.Item label={t('detailTransport')}>
              {model.transportLabel ?? t('notAvailable')}
            </Descriptions.Item>
            <Descriptions.Item label={t('detailEnabled')}>
              <Tag color={model.enabled ? 'green' : 'default'}>
                {model.enabled ? t('statusEnabled') : t('statusDisabled')}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('detailDiagnostics')}>
              <Space direction="vertical" size={2}>
                {model.diagnostics.map((item) => (
                  <Typography.Text key={item.label} type="secondary">
                    {diagnosticLine(item)}
                  </Typography.Text>
                ))}
              </Space>
            </Descriptions.Item>
          </Descriptions>
          <Alert style={{ marginTop: 16 }} type="info" showIcon message={t('detailNoSecret')} />
          <Space style={{ marginTop: 20, justifyContent: 'flex-end', width: '100%' }} wrap>
            <Popconfirm
              title={t('confirmDeleteBinding')}
              description={t('deleteHelp')}
              okText={t('deleteBinding')}
              cancelText={t('cancel')}
              okButtonProps={{ danger: true }}
              onConfirm={onDelete}
            >
              <Button danger loading={deleteLoading}>{t('deleteBinding')}</Button>
            </Popconfirm>
            <Button type="primary" onClick={onEdit}>{t('edit')}</Button>
          </Space>
        </>
      )}
    </Drawer>
  );
}
