import { afterEach, describe, expect, it } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Form } from 'antd';
import { i18n } from '@/shared/i18n';
import { normalizeRunPolicy, RunPolicyEditor } from './RunPolicyEditor';

afterEach(async () => { cleanup(); await act(async () => { await i18n.changeLanguage('zh-CN'); }); });

it('preserves pointer, keyboard and bounds while localizing field labels within the installed handler-name limit', async () => {
  await i18n.changeLanguage('zh-CN');
  render(<Form initialValues={{ sessionMode: 'ISOLATED', overlapPolicy: 'SKIP', misfirePolicy: 'SKIP_ALL', startDeadlineSeconds: 1, affinityTimeoutSeconds: 0 }}><RunPolicyEditor /></Form>);
  const user = userEvent.setup();
  const deadline = screen.getAllByRole('spinbutton')[0];
  await user.click(screen.getAllByRole('button', { name: 'Increase Value' })[0]);
  expect(deadline).toHaveValue('2');
  await user.click(deadline); await user.keyboard('{ArrowDown}');
  expect(deadline).toHaveValue('1');
  await user.keyboard('{ArrowDown}'); expect(deadline).toHaveValue('1');
  // The installed public API cannot override these parent names (Ruling62).
  expect(screen.getAllByRole('button', { name: 'Increase Value' })).toHaveLength(2);
  await user.clear(deadline); await user.type(deadline, '42');
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(screen.getByRole('spinbutton', { name: i18n.t('scheduledTask:form.startDeadline') })).toBe(deadline);
  expect(deadline).toHaveValue('42');
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
  expect(screen.getByRole('spinbutton', { name: i18n.t('scheduledTask:form.startDeadline') })).toBe(deadline);
  expect(deadline).toHaveValue('42');
});

describe('normalizeRunPolicy', () => {
  it('disallows ALLOW and requires positive affinity for continuous sessions', () => {
    expect(normalizeRunPolicy({ sessionMode: 'CONTINUOUS', overlapPolicy: 'ALLOW', affinityTimeoutSeconds: 0, startDeadlineSeconds: 0 }))
      .toEqual({ sessionMode: 'CONTINUOUS', overlapPolicy: 'SKIP', affinityTimeoutSeconds: 1, startDeadlineSeconds: 1 });
  });
});
