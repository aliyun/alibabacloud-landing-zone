import { useState } from 'react';
import { Alert, Button, Card, Collapse, Form, Input, message, Select, Space, Upload } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import type { UploadFile } from 'antd/es/upload/interface';
import dayjs, { type Dayjs } from 'dayjs';
import { listSquads, getSquadMembers } from '@/features/squad/api';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useCreateScheduledTask, useUploadScheduledTaskDocuments } from './hooks';
import { normalizeRunPolicy, RunPolicyEditor } from './components/RunPolicyEditor';
import { ScheduleEditor } from './components/ScheduleEditor';
import { transitionScheduledTask } from './api';
import type { CreateScheduledTaskBody } from './types';

const { TextArea } = Input;
interface CreateFormValues { name: string; instructionMd: string; squadId: number; initialAgentId: number; scheduleType: 'ONCE' | 'CRON'; runAtDate?: Dayjs; runAtTime?: Dayjs; cronExpression?: string; sessionMode: 'ISOLATED' | 'CONTINUOUS'; overlapPolicy: 'SKIP' | 'QUEUE' | 'ALLOW'; misfirePolicy: 'SKIP_ALL' | 'FIRE_LATEST' | 'FIRE_ALL'; startDeadlineSeconds?: number; affinityTimeoutSeconds?: number; initialStatus?: 'ACTIVE' | 'PAUSED'; }
export function initialStatusForCreate(requestedStatus: 'ACTIVE' | 'PAUSED', documentCount: number) {
  return documentCount > 0 && requestedStatus === 'ACTIVE' ? 'PAUSED' : requestedStatus;
}

export function ScheduledTaskCreatePage() {
  const navigate = useNavigate(); const accessCommand = useAccessCommand();
  const [form] = Form.useForm<CreateFormValues & { schedulePreset: string }>();
  const [files, setFiles] = useState<UploadFile[]>([]); const [selectedAgentId, setSelectedAgentId] = useState<number>();
  const squadId = Form.useWatch('squadId', form); const createTask = useCreateScheduledTask(); const uploadDocuments = useUploadScheduledTaskDocuments();
  const { data: squadsPage } = useQuery({ queryKey: ['squads', 'scheduled-task-selector'], queryFn: () => listSquads({ pageNum: 1, pageSize: 100 }) });
  const { data: members = [], isLoading: membersLoading } = useQuery({ queryKey: ['squads', squadId, 'members'], queryFn: () => getSquadMembers(squadId!), enabled: Boolean(squadId) });
  const submit = (values: CreateFormValues) => accessCommand('READ_WRITE', '创建定时任务', async () => {
    const runAt = values.runAtDate && values.runAtTime ? dayjs(values.runAtDate).hour(values.runAtTime.hour()).minute(values.runAtTime.minute()).second(0).millisecond(0).toISOString() : undefined;
    const requestedStatus = values.initialStatus ?? 'ACTIVE';
    // A task must not become scanner-eligible before its selected requirement documents are attached.
    const initialStatus = initialStatusForCreate(requestedStatus, files.length);
    const policy = normalizeRunPolicy({ sessionMode: values.sessionMode ?? 'ISOLATED', overlapPolicy: values.overlapPolicy ?? 'SKIP', affinityTimeoutSeconds: values.affinityTimeoutSeconds, startDeadlineSeconds: values.startDeadlineSeconds ?? 300 });
    const body: CreateScheduledTaskBody = { name: values.name, instructionMd: values.instructionMd, squadId: values.squadId, initialAgentId: values.initialAgentId, scheduleType: values.scheduleType, timezone: 'Asia/Shanghai', ...policy, misfirePolicy: values.misfirePolicy ?? 'SKIP_ALL', initialStatus, ...(values.scheduleType === 'ONCE' ? { runAt: runAt! } : { cronExpression: values.cronExpression }) };
    try {
      const task = await createTask.mutateAsync(body);
      const rawFiles: File[] = files.flatMap((file) => file.originFileObj ? [file.originFileObj] : []);
      try {
        if (rawFiles.length) await uploadDocuments.mutateAsync({ id: task.id, files: rawFiles });
      } catch {
        message.error('任务已保存为暂停，需求文档上传失败；请在任务详情重试上传后再启用。');
        navigate(`/scheduled-tasks/${task.id}`);
        return;
      }
      if (requestedStatus === 'ACTIVE' && initialStatus === 'PAUSED') {
        try { await transitionScheduledTask(task.id, 'enable', task.version); }
        catch {
          message.error('需求文档已上传，但启用失败；任务仍为暂停状态，请在任务详情重试启用。');
          navigate(`/scheduled-tasks/${task.id}`);
          return;
        }
      }
      message.success(requestedStatus === 'ACTIVE' ? '定时任务已创建并启用' : '定时任务已保存为暂停');
      navigate(`/scheduled-tasks/${task.id}`);
    } catch { /* shared interceptor preserves the form for retry */ }
  });
  return <Card title="新建 定时任务" style={{ maxWidth: 820, margin: '0 auto' }}><Form form={form} layout="vertical" onFinish={submit} initialValues={{ scheduleType: 'CRON', schedulePreset: 'daily', cronExpression: '0 0 2 * * *', sessionMode: 'ISOLATED', overlapPolicy: 'SKIP', misfirePolicy: 'SKIP_ALL', startDeadlineSeconds: 300, affinityTimeoutSeconds: 0, initialStatus: 'ACTIVE' }}>
    <Form.Item name="name" label="任务名称" rules={[{ required: true, message: '任务名称不能为空' }]}><Input maxLength={200} placeholder="例如：主干夜间回归" /></Form.Item>
    <Form.Item name="instructionMd" label="任务指令" rules={[{ required: true, message: '请描述希望数字人周期完成的工作' }]}><TextArea rows={6} placeholder="支持 Markdown。说明任务目标、输入、完成标准与注意事项。" /></Form.Item>
    <Form.Item name="squadId" label="小队" rules={[{ required: true, message: '请选择小队' }]}><Select showSearch optionFilterProp="label" options={(squadsPage?.list ?? []).map((squad) => ({ value: squad.id, label: squad.name }))} onChange={() => form.setFieldValue('initialAgentId', undefined)} placeholder="选择执行小队" /></Form.Item>
    <Form.Item name="initialAgentId" label="首个数字人" rules={[{ required: true, message: '请选择首个数字人' }]}><Select loading={membersLoading} disabled={!squadId} onChange={setSelectedAgentId} options={members.map((member) => ({ value: member.agentId, label: member.agentName }))} placeholder="选择该小队中的首个数字人" /></Form.Item>
    {(() => { const selectedMember = members.find((member) => member.agentId === Number(selectedAgentId)); return selectedMember ? <Alert type="info" showIcon message={`${selectedMember.agentName} 将按 ${selectedMember.sdlcName || '未绑定 SDLC（直接接受调度指令）'} 执行`} description={selectedMember.sdlcSteps?.length ? selectedMember.sdlcSteps.map((step) => `${step.stepOrder}. ${step.name}`).join(' → ') : undefined} style={{ marginBottom: 16 }} /> : null; })()}
    <Form.Item label="时区"><Input value="Asia/Shanghai" disabled /></Form.Item><ScheduleEditor timezone="Asia/Shanghai" />
    <Collapse items={[{ key: 'policy', label: '运行策略与连续会话设置', children: <RunPolicyEditor /> }]} />
    <Form.Item label="需求文档" style={{ marginTop: 24 }} extra="任务创建成功后自动上传，并随每次运行冻结。"><Upload.Dragger multiple beforeUpload={() => false} fileList={files} onChange={({ fileList }) => setFiles(fileList)}><p className="ant-upload-drag-icon"><InboxOutlined /></p><p className="ant-upload-text">点击或拖拽上传需求文档</p></Upload.Dragger></Form.Item>
    <Space><Button type="primary" htmlType="submit" loading={createTask.isPending} onClick={() => form.setFieldValue('initialStatus', 'ACTIVE')}>创建并启用</Button><Button htmlType="submit" loading={createTask.isPending} onClick={() => form.setFieldValue('initialStatus', 'PAUSED')}>保存为暂停</Button><Button onClick={() => navigate('/scheduled-tasks')}>取消</Button></Space>
  </Form></Card>;
}
