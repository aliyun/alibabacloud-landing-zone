import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { Modal, Form, Select, message } from 'antd';
import { getMentionCandidates } from '../api';
import type { Participant } from '@/shared/types/workitem';
import { useAssignWorkitem } from '../hooks';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

interface AssignHumanModalProps {
  open: boolean;
  workitemId: number | string;
  onClose: () => void;
}

export function AssignHumanModal({ open, workitemId, onClose }: AssignHumanModalProps) {
  const { t } = useTranslation('workitem');
  const [form] = Form.useForm();
  useLocalizedValidation(form, open);
  const assignMut = useAssignWorkitem();
  const accessCommand = useAccessCommand();

  const { data: candidates = [], isFetching: candidatesLoading } = useQuery({
    queryKey: ['workitem', workitemId, 'human-assign-candidates'],
    queryFn: () => getMentionCandidates(workitemId, undefined, 100),
    enabled: open,
  });
  const humans = candidates.filter(
    (candidate: Participant) => candidate.targetType === 'HUMAN' || !candidate.isAgent,
  );

  const handleOk = async () => {
    let values;
    try {
      values = await form.validateFields();
    } catch {
      // antd already renders the field-level validation errors
      return;
    }
    accessCommand('READ_WRITE', t('detail.assignHumanCommand'), async () => {
      try {
        await assignMut.mutateAsync({
          id: workitemId,
          assigneeType: 'HUMAN',
          assigneeRef: values.userId,
        });
        message.success(i18n.t('workitem:assignment.success'));
        form.resetFields();
        onClose();
      } catch {
        // ApiError already surfaced by interceptor
      }
    });
  };

  return (
    <Modal
      title={t('assignment.title')}
      open={open}
      onOk={handleOk}
      onCancel={() => {
        form.resetFields();
        onClose();
      }}
      confirmLoading={assignMut.isPending}
      okText={t('assignment.submit')}
      cancelText={t('detail.cancel')}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="userId"
          label={t('assignment.assignee')}
          rules={[{ required: true, message: t('assignment.required') }]}
        >
          <Select
            placeholder={t('assignment.placeholder')}
            showSearch
            loading={candidatesLoading}
            optionFilterProp="label"
            options={humans.map((user: Participant) => ({
              value: user.userId,
              label: user.displayId ? `${user.name} (${user.displayId})` : user.name,
            }))}
            notFoundContent={candidatesLoading ? t('assignment.loading') : t('assignment.empty')}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
