import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { MemberRoleSettingsPage } from './MemberRoleSettingsPage';

vi.mock('./MembersPage', () => ({
  MembersPage: () => <div>成员管理内容</div>,
}));

describe('MemberRoleSettingsPage', () => {
  it('renders only member management without a role tab', () => {
    render(
      <MemoryRouter>
        <MemberRoleSettingsPage />
      </MemoryRouter>,
    );

    expect(screen.getByText('成员管理内容')).toBeInTheDocument();
    expect(screen.queryByText('角色管理')).not.toBeInTheDocument();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
  });
});
