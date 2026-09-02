import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Modal, Form, Select, DatePicker, message } from 'antd';
import type { Dayjs } from 'dayjs';
import { listSquads, getSquadMembers } from '@/features/squad/api';
import type { SquadMember } from '@/features/squad/api';
import { ApiError } from '@/shared/types/common';
import { useAssignWorkitem } from '../hooks';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import {
  readClarificationPrefill,
  clearClarificationPrefill,
} from '../clarification/prefill';

interface StartDeliveryModalProps {
  open: boolean;
  workitemId: number | string;
  /** When true the workitem already has an SDLC bound; this is a re-assign, so the SDLC picker is hidden. */
  hasSdlc?: boolean;
  onClose: () => void;
}

export function StartDeliveryModal({ open, workitemId, hasSdlc, onClose }: StartDeliveryModalProps) {
  const [form] = Form.useForm();
  const assignMut = useAssignWorkitem();
  const accessCommand = useAccessCommand();
  const squadId = Form.useWatch('squadId', form);

  // Prefill from the most recent AI clarification selection when opening the
  // modal for a fresh (non-reassign) delivery.
  useEffect(() => {
    if (!open) return;
    if (hasSdlc) return;
    const prefill = readClarificationPrefill(workitemId);
    if (!prefill) return;
    form.setFieldsValue({ squadId: prefill.squadId, agentId: prefill.agentId });
  }, [open, hasSdlc, workitemId, form]);

  const { data: squads } = useQuery({
    queryKey: ['squads', 'delivery-start'],
    queryFn: () => listSquads({ pageNum: 1, pageSize: 100 }),
    enabled: open,
  });
  const { data: members = [], isFetching: membersLoading } = useQuery({
    queryKey: ['squad-members', squadId],
    queryFn: () => getSquadMembers(squadId),
    enabled: open && !!squadId,
  });

  const handleOk = async () => {
    const values = await form.validateFields();
    accessCommand('READ_WRITE', hasSdlc ? '重新指派工单' : '启动工单交付', async () => {
      try {
        await assignMut.mutateAsync({
          id: workitemId,
          assigneeRef: values.agentId,
          squadId: values.squadId,
          scheduledStartAt: values.scheduledStartAt
            ? (values.scheduledStartAt as Dayjs).toISOString()
            : undefined,
        });
        message.success(hasSdlc ? '已重新指派' : '已启动交付');
        if (!hasSdlc) {
          clearClarificationPrefill(workitemId);
        }
        form.resetFields();
        onClose();
      } catch (err) {
        message.error(err instanceof ApiError ? err.message : '启动交付失败，请稍后重试');
      }
    });
  };

  return (
    <Modal
      title={hasSdlc ? '重新指派' : '启动交付'}
      open={open}
      onOk={handleOk}
      onCancel={() => {
        form.resetFields();
        onClose();
      }}
      confirmLoading={assignMut.isPending}
      okText={hasSdlc ? '重新指派' : '启动交付'}
      cancelText="取消"
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item name="squadId" label="小队" rules={[{ required: true, message: '请选择小队' }]}>
          <Select
            placeholder="选择交付小队"
            options={(squads?.list || []).map((s) => ({ value: s.id, label: s.name }))}
            onChange={() => form.setFieldValue('agentId', undefined)}
          />
        </Form.Item>
        <Form.Item name="agentId" label="首步执行 Agent" rules={[{ required: true, message: '请选择执行 Agent' }]}>
          <Select
            placeholder={squadId ? '选择小队成员' : '请先选择小队'}
            disabled={!squadId}
            loading={membersLoading}
            options={members.map((member: SquadMember) => ({
              value: member.agentId,
              label: member.roleCode ? `${member.agentName} (${member.roleCode})` : member.agentName,
            }))}
          />
        </Form.Item>
        <Form.Item name="scheduledStartAt" label="计划执行时间（可选）">
          <DatePicker
            showTime
            style={{ width: '100%' }}
            placeholder="留空则立即执行"
            disabledDate={(current) => !!current && current.isBefore(new Date(), 'minute')}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
