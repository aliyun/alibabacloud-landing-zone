import { useState } from 'react';
import { Button, DatePicker, Modal, Space, message } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useUpdateScheduledStart } from '../hooks';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

interface ScheduledStartControlProps {
  workitemId: number | string;
  assigneeType: string;
  scheduledStartAt: string | null | undefined;
}

/** 详情页计划执行行：仅对已指派数字员工且存在计划时间的工单展示。 */
export function ScheduledStartControl({ workitemId, assigneeType, scheduledStartAt }: ScheduledStartControlProps) {
  const [editOpen, setEditOpen] = useState(false);
  const [editValue, setEditValue] = useState<Dayjs | null>(null);
  const mutation = useUpdateScheduledStart(workitemId);
  const accessCommand = useAccessCommand();

  if (assigneeType !== 'AGENT' || !scheduledStartAt) {
    return null;
  }

  const run = (action: string, call: () => Promise<unknown>) =>
    accessCommand('READ_WRITE', action, async () => {
      try {
        await call();
      } catch {
        // ApiError already surfaced by interceptor
      }
    });

  const handleEditOk = () => {
    if (!editValue || editValue.isBefore(dayjs(), 'minute')) {
      message.warning('计划执行时间必须是将来的时间点');
      return;
    }
    run('调整工单计划执行时间', () =>
      mutation.mutateAsync({ scheduledStartAt: editValue.toISOString() }).then(() => setEditOpen(false)),
    );
  };

  return (
    <div style={{ marginTop: 8, fontSize: 12, color: '#666', display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
      <span>计划执行: {new Date(scheduledStartAt).toLocaleString('zh-CN')}</span>
      <Space size={4}>
        <Button
          size="small"
          onClick={() => {
            setEditValue(dayjs(scheduledStartAt));
            setEditOpen(true);
          }}
        >
          修改
        </Button>
        <Button size="small" onClick={() => run('立即执行工单', () => mutation.mutateAsync({ executeNow: true }))}>
          立即执行
        </Button>
        <Button size="small" danger onClick={() => run('取消工单定时执行', () => mutation.mutateAsync({ scheduledStartAt: null }))}>
          取消定时
        </Button>
      </Space>
      <Modal
        title="修改计划执行时间"
        open={editOpen}
        onOk={handleEditOk}
        onCancel={() => setEditOpen(false)}
        confirmLoading={mutation.isPending}
        okText="保存"
        cancelText="取消"
        destroyOnHidden
      >
        <DatePicker
          showTime
          style={{ width: '100%' }}
          value={editValue}
          onChange={(value) => setEditValue(value)}
          disabledDate={(current) => !!current && current.isBefore(dayjs(), 'minute')}
        />
      </Modal>
    </div>
  );
}
