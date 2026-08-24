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
  const [form] = Form.useForm();
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
    accessCommand('READ_WRITE', '指派工单给真人', async () => {
      try {
        await assignMut.mutateAsync({
          id: workitemId,
          assigneeType: 'HUMAN',
          assigneeRef: values.userId,
        });
        message.success('已指派给真人');
        form.resetFields();
        onClose();
      } catch {
        // ApiError already surfaced by interceptor
      }
    });
  };

  return (
    <Modal
      title="指派给真人"
      open={open}
      onOk={handleOk}
      onCancel={() => {
        form.resetFields();
        onClose();
      }}
      confirmLoading={assignMut.isPending}
      okText="指派"
      cancelText="取消"
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="userId"
          label="指派给"
          rules={[{ required: true, message: '请选择指派对象' }]}
        >
          <Select
            placeholder="选择真人用户"
            showSearch
            loading={candidatesLoading}
            optionFilterProp="label"
            options={humans.map((user: Participant) => ({
              value: user.userId,
              label: user.displayId ? `${user.name} (${user.displayId})` : user.name,
            }))}
            notFoundContent={candidatesLoading ? '加载中…' : '暂无可指派的真人用户'}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
