import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Form } from 'antd';
import { i18n } from '@/shared/i18n';
import { mockRoles } from '@/test/mocks/workspaceRoles';
import { MemberAccessModal } from './MemberAccessModal';

const member = {
  userId: 2,
  username: 'dev',
  email: 'dev@example.com',
  nickname: '开发者',
  joinedAt: '2026-07-01',
  owner: false,
  accessLevel: 'READ_WRITE' as const,
  identityTags: ['开发'],
  readOnlyDomains: [],
  roleId: 12, roleCode: 'READ_WRITE', roleName: null,
};

describe('MemberAccessModal', () => {
  it('skips an initial closed reset but still clears real member drafts on close and reseeds another member', async () => {
    const original = Form.useForm;
    const reset = vi.fn();
    const useForm = vi.spyOn(Form, 'useForm').mockImplementation((...args: Parameters<typeof original>) => {
      const result = original(...args);
      const instance = result[0];
      const actual = instance.resetFields;
      if (!vi.isMockFunction(actual)) vi.spyOn(instance, 'resetFields').mockImplementation((...fields: Parameters<typeof actual>) => { reset(); return actual(...fields); });
      return result;
    });
    const props = { loading: false, onClose: vi.fn(), onConfirm: vi.fn() };
    try {
      const view = render(<MemberAccessModal roles={mockRoles} {...props} open={false} member={null} />);
      expect(reset).not.toHaveBeenCalled();
      await act(async () => { await i18n.changeLanguage('en-US'); });
      expect(reset).not.toHaveBeenCalled();
      await act(async () => { await i18n.changeLanguage('zh-CN'); });
      view.rerender(<MemberAccessModal roles={mockRoles} {...props} open member={member} />);
      await userEvent.type(screen.getByRole('combobox', { name: '身份标签' }), 'raw 用户{enter}');
      expect(screen.getAllByText('raw 用户').length).toBeGreaterThan(0);
      view.rerender(<MemberAccessModal roles={mockRoles} {...props} open={false} member={null} />);
      expect(reset).toHaveBeenCalledTimes(1);
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      view.rerender(<MemberAccessModal roles={mockRoles} {...props} open member={{ ...member, userId: 3, identityTags: ['另一位'] }} />);
      expect(screen.getAllByText('另一位').length).toBeGreaterThan(0);
      expect(screen.queryByText('raw 用户')).not.toBeInTheDocument();
      expect(props.onConfirm).not.toHaveBeenCalled();
    } finally { useForm.mockRestore(); await act(async () => { await i18n.changeLanguage('zh-CN'); }); }
  });

  it('submits one role and normalized identity tags', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(
      <MemberAccessModal roles={mockRoles}
        open
        member={member}
        loading={false}
        onClose={() => undefined}
        onConfirm={onConfirm}
      />,
    );

    await user.click(screen.getByRole('combobox', { name: '角色' }));
    await user.click(screen.getByText('交付协作 / Delivery'));
    await user.type(screen.getByRole('combobox', { name: '身份标签' }), ' 验收员 {enter}');
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith({
      roleId: 14,
      identityTags: ['开发', '验收员'],
    }));
  });

  it('rejects more than eight tags and tags longer than 32 characters', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    const firstRender = render(
      <MemberAccessModal roles={mockRoles}
        open
        member={{ ...member, identityTags: Array.from({ length: 8 }, (_, i) => `标签${i}`) }}
        loading={false}
        onClose={() => undefined}
        onConfirm={onConfirm}
      />,
    );

    await user.type(screen.getByRole('combobox', { name: '身份标签' }), '第九个{enter}');
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));
    expect(await screen.findByText('身份标签最多 8 项')).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();

    firstRender.unmount();
    render(
      <MemberAccessModal roles={mockRoles}
        open
        member={{ ...member, identityTags: [] }}
        loading={false}
        onClose={() => undefined}
        onConfirm={onConfirm}
      />,
    );
    await user.type(
      screen.getByRole('combobox', { name: '身份标签' }),
      `${'长'.repeat(33)}{enter}`,
    );
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));
    expect(await screen.findByText('每项身份标签最多 32 个字符')).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
