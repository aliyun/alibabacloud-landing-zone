import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '@/shared/ui/PageHeading';
import { PageBackButton } from '@/shared/ui/PageBackButton';
import { Button, Card, Form, Input, Select, Space, Spin } from 'antd';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { getScheduledTask, updateScheduledTask } from './api';
import type { UpdateScheduledTaskBody } from './types';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';

export function ScheduledTaskEditPage() {
  const { t } = useTranslation('scheduledTask');
  const id = Number(useParams<{ id: string }>().id); const navigate = useNavigate(); const access = useAccessCommand(); const [form] = Form.useForm<UpdateScheduledTaskBody>();
  const task = useQuery({ queryKey: ['scheduled-tasks', id], queryFn: () => getScheduledTask(id), enabled: Number.isFinite(id) });
  useLocalizedValidation(form, !task.isLoading && Boolean(task.data));
  const save = useMutation({ mutationFn: (body: UpdateScheduledTaskBody) => updateScheduledTask(id, body), onSuccess: () => navigate(`/scheduled-tasks/${id}`) });
  if (task.isLoading) return <Spin style={{ display: 'block', margin: 80 }} />; if (!task.data) return null;
  return <Card className="aw-content-card" title={<PageHeading title={<span className="aw-detail-title"><PageBackButton to={`/scheduled-tasks/${id}`} label={t('actions.backToDetail')} /><span>{t('edit.title')}</span></span>} />}><Form form={form} layout="vertical" initialValues={task.data} onFinish={(value) => access('READ_WRITE', t('edit.title'), () => { const current = task.data!; save.mutate({ name: value.name ?? current.name, instructionMd: value.instructionMd ?? current.instructionMd, squadId: current.squadId, initialAgentId: current.initialAgentId, scheduleType: current.scheduleType, runAt: current.runAt ?? undefined, cronExpression: value.cronExpression ?? current.cronExpression ?? undefined, timezone: current.timezone, sessionMode: value.sessionMode ?? current.sessionMode, overlapPolicy: value.overlapPolicy ?? current.overlapPolicy, misfirePolicy: value.misfirePolicy ?? current.misfirePolicy, startDeadlineSeconds: current.startDeadlineSeconds ?? undefined, affinityTimeoutSeconds: current.affinityTimeoutSeconds ?? undefined, version: current.version }); })}><Form.Item name="name" label={t('form.name')} rules={[{ required: true, message: t('validation.nameRequired') }]}><Input /></Form.Item><Form.Item name="instructionMd" label={t('form.instruction')} rules={[{ required: true, message: t('validation.instructionRequired') }]}><Input.TextArea rows={8} /></Form.Item><Form.Item name="cronExpression" label={t('form.cron')}><Input disabled={task.data.scheduleType !== 'CRON'} /></Form.Item><Form.Item name="sessionMode" label={t('form.sessionMode')}><Select options={[{ value: 'ISOLATED', label: t('session.isolated') }, { value: 'CONTINUOUS', label: t('session.continuous') }]} /></Form.Item><Form.Item name="overlapPolicy" label={t('form.overlap')}><Select options={[{ value: 'SKIP', label: t('overlap.skip') }, { value: 'QUEUE', label: t('overlap.queue') }, { value: 'ALLOW', label: t('overlap.allow') }]} /></Form.Item><Form.Item name="misfirePolicy" label={t('form.misfire')}><Select options={[{ value: 'SKIP_ALL', label: t('misfire.skipAll') }, { value: 'FIRE_LATEST', label: t('misfire.fireLatest') }, { value: 'FIRE_ALL', label: t('misfire.fireAll') }]} /></Form.Item><Space><Button type="primary" htmlType="submit" loading={save.isPending}>{t('actions.save')}</Button><Button onClick={() => navigate(`/scheduled-tasks/${id}`)}>{t('actions.cancel')}</Button></Space></Form></Card>;
}
