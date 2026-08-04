import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { OwnerTransferModal } from './OwnerTransferModal';

const candidates = [
  {
    userId: 2,
    username: 'dev',
    email: 'dev@example.com',
    nickname: '开发者',
    joinedAt: '2026-07-01',
    owner: false,
    accessLevel: 'READ_WRITE' as const,
    identityTags: ['开发'],
  },
];

describe('OwnerTransferModal', () => {
  it('requires an explicit target selection before transfer', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(
      <OwnerTransferModal
        open
        candidates={candidates}
        loading={false}
        onClose={() => undefined}
        onConfirm={onConfirm}
      />,
    );

    const dialog = screen.getByRole('dialog', { name: '移交组织 Owner' });
    const submit = within(dialog).getByRole('button', { name: '确认移交' });
    expect(submit).toBeDisabled();
    expect(onConfirm).not.toHaveBeenCalled();

    await user.click(within(dialog).getByRole('combobox', { name: '目标成员' }));
    await user.click(await screen.findByText('开发者 (dev@example.com)'));
    expect(submit).toBeEnabled();
    await user.click(submit);
    expect(onConfirm).toHaveBeenCalledWith(2);
  });
});
