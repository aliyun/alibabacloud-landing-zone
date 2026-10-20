import { useState } from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { Form, Input, Modal } from 'antd';
import { useTranslation } from 'react-i18next';
import { afterEach, expect, it, vi } from 'vitest';
import { i18n } from './index';
import { useLocalizedValidation } from './useLocalizedValidation';

afterEach(async () => { cleanup(); vi.restoreAllMocks(); await i18n.changeLanguage('zh-CN'); });

it('refreshes retained errors when a real modal reopens after a hidden language change, preserving raw drafts and pristine fields', async () => {
  await i18n.changeLanguage('zh-CN');
  const reads = vi.fn();
  const originalUseForm = Form.useForm;
  vi.spyOn(Form, 'useForm').mockImplementation((...args: Parameters<typeof originalUseForm>) => {
    const result = originalUseForm(...args);
    if (!vi.isMockFunction(result[0].getFieldsError)) {
      const originalRead = result[0].getFieldsError.bind(result[0]);
      vi.spyOn(result[0], 'getFieldsError').mockImplementation((...fields: Parameters<typeof originalRead>) => { reads(); return originalRead(...fields); });
    }
    return result;
  });
  function RetainedModal() {
    const { t } = useTranslation('auth');
    const [open, setOpen] = useState(true);
    const [form] = Form.useForm();
    useLocalizedValidation(form, open);
    return <>
      <button onClick={() => setOpen(true)}>reopen</button>
      <Modal open={open} onCancel={() => setOpen(false)} onOk={() => setOpen(false)}>
        <Form form={form}>
          <Form.Item name="username" label={t('fields.username')} rules={[{ required: true, message: t('fields.usernameRequired') }]}><Input /></Form.Item>
          <Form.Item name="password" label={t('fields.password')} rules={[{ required: true, message: t('fields.passwordRequired') }]}><Input /></Form.Item>
          <Form.Item name="draft" label="raw draft"><Input /></Form.Item>
        </Form>
      </Modal>
    </>;
  }
  render(<RetainedModal />);
  const username = screen.getByLabelText('用户名');
  const draft = screen.getByLabelText('raw draft');
  fireEvent.change(draft, { target: { value: '用户 {{raw}} <link>' } });
  fireEvent.change(username, { target: { value: 'x' } });
  fireEvent.change(username, { target: { value: '' } });
  expect(await screen.findByText('请输入用户名')).toBeInTheDocument();
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'OK' }));
  reads.mockClear();
  await act(async () => { await i18n.changeLanguage('en-US'); });
  expect(reads).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'reopen' }));
  expect(await screen.findByText('Enter your username')).toBeInTheDocument();
  expect(screen.queryByText('Enter your password')).not.toBeInTheDocument();
  expect(screen.getByLabelText('Username')).toBe(username);
  expect(screen.getByLabelText('raw draft')).toBe(draft);
  expect(draft).toHaveValue('用户 {{raw}} <link>');
});
