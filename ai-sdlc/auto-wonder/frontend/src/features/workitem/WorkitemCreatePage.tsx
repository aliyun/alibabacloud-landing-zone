import { Card, Form, Input, Select, Button, DatePicker, message } from 'antd';
import type { Dayjs } from 'dayjs';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useCreateWorkitem } from './hooks';
import { listSquads, getSquadMembers } from '@/features/squad/api';
import type { SquadMember } from '@/features/squad/api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

const { TextArea } = Input;

export function WorkitemCreatePage() {
  const navigate = useNavigate();
  const [form] = Form.useForm();
  const createMut = useCreateWorkitem();
  const accessCommand = useAccessCommand();
  const squadId = Form.useWatch('squadId', form);
  const agentId = Form.useWatch('agentId', form);

  const { data: squads } = useQuery({
    queryKey: ['squads', 'workitem-create'],
    queryFn: () => listSquads({ pageNum: 1, pageSize: 100 }),
  });
  const { data: members = [], isFetching: membersLoading } = useQuery({
    queryKey: ['squad-members', squadId],
    queryFn: () => getSquadMembers(squadId),
    enabled: !!squadId,
  });

  const handleSubmit = (values: {
    workType: string;
    title: string;
    contentMd: string;
    priority: number;
    squadId?: number;
    agentId?: number;
    scheduledStartAt?: Dayjs;
  }) => {
    accessCommand('READ_WRITE', '创建工单', async () => {
      try {
        const wi = await createMut.mutateAsync({
          workType: values.workType,
          title: values.title,
          contentMd: values.contentMd,
          priority: values.priority,
          ...(values.agentId != null
            ? {
                assigneeType: 'AGENT',
                assigneeRef: values.agentId,
                ...(values.squadId != null ? { squadId: values.squadId } : {}),
                ...(values.scheduledStartAt
                  ? { scheduledStartAt: values.scheduledStartAt.toISOString() }
                  : {}),
              }
            : {}),
        });
        message.success('工单创建成功');
        navigate(`/workitems/${wi.id}`);
      } catch {
        return; // create failed; interceptor already showed the error, stay on form to retry
      }
    });
  };

  return (
    <Card title="新建工单" style={{ maxWidth: 720, margin: '0 auto' }}>
      <Form form={form} layout="vertical" onFinish={handleSubmit} initialValues={{ workType: 'REQ', priority: 2 }}>
        <Form.Item name="workType" label="类型" rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'REQ', label: '需求' },
              { value: 'TASK', label: '任务' },
              { value: 'BUG', label: '缺陷' },
            ]}
          />
        </Form.Item>
        <Form.Item name="title" label="标题" rules={[{ required: true, message: '标题不能为空' }]}>
          <Input placeholder="请输入工单标题" maxLength={200} />
        </Form.Item>
        <Form.Item name="contentMd" label="描述" rules={[{ required: true, message: '描述不能为空' }]}>
          <TextArea rows={6} placeholder="请输入工单描述（支持 Markdown）" />
        </Form.Item>
        <Form.Item name="priority" label="优先级">
          <Select
            options={[
              { value: 0, label: 'P0 - 紧急' },
              { value: 1, label: 'P1 - 高' },
              { value: 2, label: 'P2 - 中' },
              { value: 3, label: 'P3 - 低' },
            ]}
          />
        </Form.Item>
        <Card type="inner" title="定时交付（可选）" style={{ marginBottom: 24 }}>
          <Form.Item name="squadId" label="交付小队">
            <Select
              allowClear
              placeholder="选择后由数字员工交付"
              options={(squads?.list || []).map((s) => ({ value: s.id, label: s.name }))}
              onChange={() => form.setFieldValue('agentId', undefined)}
            />
          </Form.Item>
          <Form.Item name="agentId" label="执行 Agent">
            <Select
              allowClear
              placeholder={squadId ? '选择执行 Agent' : '请先选择交付小队'}
              disabled={!squadId}
              loading={membersLoading}
              options={members.map((member: SquadMember) => ({
                value: member.agentId,
                label: member.roleCode ? `${member.agentName} (${member.roleCode})` : member.agentName,
              }))}
            />
          </Form.Item>
          <Form.Item name="scheduledStartAt" label="定时执行时间">
            <DatePicker
              showTime
              style={{ width: '100%' }}
              placeholder="留空则立即执行"
              disabled={!agentId}
              disabledDate={(current) => !!current && current.isBefore(new Date(), 'minute')}
            />
          </Form.Item>
        </Card>
        <Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            loading={createMut.isPending}
            style={{ marginRight: 8 }}
          >
            创建
          </Button>
          <Button onClick={() => navigate('/workitems')}>取消</Button>
        </Form.Item>
      </Form>
    </Card>
  );
}
