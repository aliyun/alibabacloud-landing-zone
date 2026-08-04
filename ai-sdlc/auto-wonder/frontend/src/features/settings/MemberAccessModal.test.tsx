import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
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
};

describe('MemberAccessModal', () => {
  it('submits access level and normalized identity tags', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(
      <MemberAccessModal
        open
        member={member}
        loading={false}
        onClose={() => undefined}
        onConfirm={onConfirm}
      />,
    );

    await user.click(screen.getByLabelText('管理员权限'));
    await user.type(screen.getByRole('combobox', { name: '身份标签' }), ' 验收员 {enter}');
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith({
      accessLevel: 'ADMIN',
      identityTags: ['开发', '验收员'],
    }));
  });

  it('rejects more than eight tags and tags longer than 32 characters', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    const firstRender = render(
      <MemberAccessModal
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
      <MemberAccessModal
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
