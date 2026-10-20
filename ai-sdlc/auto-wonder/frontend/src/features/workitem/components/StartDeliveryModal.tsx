import { i18n } from '@/shared/i18n';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { useEffect, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
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

function createRestartToken(): string {
  const webCrypto = globalThis.crypto;
  if (typeof webCrypto?.randomUUID === 'function') {
    return webCrypto.randomUUID();
  }
  return `restart-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export function StartDeliveryModal({ open, workitemId, hasSdlc, onClose }: StartDeliveryModalProps) {
  const { t } = useTranslation('workitem');
  const [form] = Form.useForm();
  useLocalizedValidation(form, open);
  const assignMut = useAssignWorkitem();
  const accessCommand = useAccessCommand();
  const queryClient = useQueryClient();
  const squadId = Form.useWatch('squadId', form);
  // One token per modal session: duplicate submits and network retries of this
  // reassignment converge onto one restart round server-side; reopening the modal
  // and reassigning again is an explicit new round.
  const restartTokenRef = useRef(createRestartToken());
  useEffect(() => {
    if (open) {
      restartTokenRef.current = createRestartToken();
    }
  }, [open]);

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
    const values = await form.validateFields().catch(() => null);
    if (!values) return;
    const scheduledStartAt = values.scheduledStartAt
      ? (values.scheduledStartAt as Dayjs).toISOString()
      : undefined;
    accessCommand('READ_WRITE', hasSdlc ? t('start.reassignCommand') : t('start.deliveryCommand'), async () => {
      try {
        await assignMut.mutateAsync({
          id: workitemId,
          assigneeRef: values.agentId,
          squadId: values.squadId,
          scheduledStartAt,
          restartToken: restartTokenRef.current,
        });
        // The restart itself is asynchronous (stop old executions, then start the
        // new round); its live progress shows in the delivery recovery panel.
        if (hasSdlc) {
          message.success(i18n.t(scheduledStartAt ? 'workitem:start.reassignedScheduled' : 'workitem:start.reassigned'));
        } else {
          message.success(i18n.t('workitem:start.started'));
        }
        queryClient.invalidateQueries({ queryKey: ['workitem-recovery', String(workitemId)] });
        if (!hasSdlc) {
          clearClarificationPrefill(workitemId);
        }
        form.resetFields();
        onClose();
      } catch (err) {
        message.error(err instanceof ApiError ? err.message : i18n.t('workitem:start.failed'));
      }
    });
  };

  return (
    <Modal
      title={hasSdlc ? t('start.reassign') : t('start.title')}
      open={open}
      onOk={handleOk}
      onCancel={() => {
        form.resetFields();
        onClose();
      }}
      confirmLoading={assignMut.isPending}
      okText={hasSdlc ? t('start.reassign') : t('start.title')}
      cancelText={t('schedule.cancelEdit')}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item name="squadId" label={t('start.squad')} rules={[{ required: true, message: t('start.squadRequired') }]}>
          <Select
            placeholder={t('start.squadPlaceholder')}
            options={(squads?.list || []).map((s) => ({ value: s.id, label: s.name }))}
            onChange={() => form.setFieldValue('agentId', undefined)}
          />
        </Form.Item>
        <Form.Item name="agentId" label={t('start.agent')} rules={[{ required: true, message: t('start.agentRequired') }]}>
          <Select
            placeholder={squadId ? t('start.memberPlaceholder') : t('start.chooseSquadFirst')}
            disabled={!squadId}
            loading={membersLoading}
            options={members
              .filter((member: SquadMember) => member.agentKind !== 'PLATFORM')
              .map((member: SquadMember) => ({
                value: member.agentId,
                label: member.roleCode ? `${member.agentName} (${member.roleCode})` : member.agentName,
              }))}
          />
        </Form.Item>
        <Form.Item name="scheduledStartAt" label={t('start.scheduledTime')}>
          <DatePicker
            showTime
            style={{ width: '100%' }}
            placeholder={t('start.timePlaceholder')}
            disabledDate={(current) => !!current && current.isBefore(new Date(), 'minute')}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
