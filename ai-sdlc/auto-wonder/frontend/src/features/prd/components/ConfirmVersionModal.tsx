import { Alert, Descriptions, Form, Modal, Radio, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { DELIVERY_MODE, type PrdDesignVersion } from '../api';

const { Text } = Typography;

interface ConfirmVersionModalProps {
  open: boolean;
  version: PrdDesignVersion | null;
  blockingCount: number;
  ignoreOpenQuestions?: boolean;
  confirming: boolean;
  hasTarget: boolean;
  onOk: (deliveryMode: string) => void;
  onCancel: () => void;
}

/**
 * Confirm a specific design version and choose the delivery mode.
 *
 * <p>Confirmation is deliberately separate from starting a delivery: this dialog only records that the
 * reviewer accepts this exact content. The digest carried by the version is the one the reviewer saw,
 * and the server refuses to confirm if it no longer matches. Choosing PRD_DRIVEN is what makes later
 * deliveries enforce this version; LEGACY leaves the workitem on its existing path.
 */
export function ConfirmVersionModal({
  open,
  version,
  blockingCount,
  ignoreOpenQuestions = false,
  confirming,
  hasTarget,
  onOk,
  onCancel,
}: ConfirmVersionModalProps) {
  const { t } = useTranslation('prd');
  const [form] = Form.useForm();

  const blocked = blockingCount > 0 && !ignoreOpenQuestions;
  const prdDrivenDisabled = !hasTarget;

  return (
    <Modal
      open={open}
      title={t('confirm.title')}
      okText={t('confirm.ok')}
      cancelText={t('confirm.cancel')}
      confirmLoading={confirming}
      okButtonProps={{ disabled: blocked }}
      onCancel={onCancel}
      onOk={() => {
        void form.validateFields().then((values) => onOk(values.deliveryMode as string));
      }}
      destroyOnHidden
    >
      {version && (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Descriptions column={1} size="small">
            <Descriptions.Item label={t('confirm.version')}>{t('versionLabel', { versionNo: version.versionNo })}</Descriptions.Item>
            <Descriptions.Item label={t('confirm.requirementCount')}>
              {version.requirementCount ?? 0}
            </Descriptions.Item>
            <Descriptions.Item label={t('confirm.digest')}>
              <Text code copyable={{ text: version.designSha256 }}>
                {version.designSha256.slice(0, 16)}
              </Text>
            </Descriptions.Item>
          </Descriptions>

          {ignoreOpenQuestions && blockingCount > 0 && <Alert type="warning" showIcon
            message={t('confirm.questionsPending', { count: blockingCount })}
            description={t('confirm.ignoreHint')} />}
          {blocked && (
            <Alert
              type="error"
              showIcon
              message={t('confirm.blocked')}
              description={t('confirm.blockedHint', { count: blockingCount })}
            />
          )}

          <Form form={form} layout="vertical" initialValues={{ deliveryMode: DELIVERY_MODE.PRD_DRIVEN }}>
            <Form.Item name="deliveryMode" label={t('confirm.modeLabel')}>
              <Radio.Group disabled={blocked}>
                <Space direction="vertical">
                  <Radio value={DELIVERY_MODE.PRD_DRIVEN} disabled={prdDrivenDisabled}>
                    {t('confirm.modePrdDriven')}
                  </Radio>
                  <Radio value={DELIVERY_MODE.LEGACY}>{t('confirm.modeLegacy')}</Radio>
                </Space>
              </Radio.Group>
            </Form.Item>
          </Form>

          {!hasTarget && (
            <Alert type="warning" showIcon message={t('confirm.noTarget')} description={t('confirm.noTargetHint')} />
          )}

          <Alert type="info" showIcon message={t('confirm.immutableHint')} />
        </Space>
      )}
    </Modal>
  );
}
