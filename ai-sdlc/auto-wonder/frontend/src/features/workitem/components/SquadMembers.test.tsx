import { describe, expect, it } from 'vitest';
import { act } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SquadMembers } from './SquadMembers';
import type { Participant } from '@/shared/types/workitem';

describe('SquadMembers', () => {
  it('renders full agent identity and executor-derived online state', () => {
    const participants: Participant[] = [
      {
        userId: 41,
        name: 'Agent Dev',
        displayId: '41',
        role: 'AGENT',
        roleName: '开发小队成员',
        isAgent: true,
        online: false,
        status: 'ONLINE',
        executorStatus: 'OFFLINE',
      },
      {
        userId: 42,
        name: 'Agent CR',
        displayId: '42',
        role: 'AGENT',
        roleName: '开发小队成员',
        isAgent: true,
        online: true,
        status: 'ONLINE',
        executorStatus: 'BUSY',
      },
    ];

    render(<SquadMembers participants={participants} />);

    expect(screen.getByText('Agent Dev')).toBeInTheDocument();
    expect(screen.getByText(/工号: 41/)).toBeInTheDocument();
    expect(screen.getByText('离线')).toBeInTheDocument();
    expect(screen.getByText('Agent CR')).toBeInTheDocument();
    expect(screen.getByText(/工号: 42/)).toBeInTheDocument();
    expect(screen.getByText('执行中')).toBeInTheDocument();
  });

  it('separates human participants from development squad members', async () => {
    const user = userEvent.setup();
    const participants: Participant[] = [
      {
        userId: 41,
        targetType: 'AGENT',
        name: 'Agent Dev',
        displayId: '41',
        role: 'AGENT',
        roleName: '开发小队成员',
        isAgent: true,
        online: false,
        status: 'ONLINE',
        executorStatus: 'OFFLINE',
      },
      {
        userId: 10000,
        name: '蔡何',
        displayId: '10000',
        role: 'HUMAN',
        roleName: '真人',
        isAgent: false,
        online: false,
        status: '0',
        executorStatus: 'OFFLINE',
      },
    ];

    render(<SquadMembers participants={participants} />);

    expect(screen.getByText('成员')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '数字人成员' })).toBeInTheDocument();
    expect(screen.getByText('Agent Dev')).toBeInTheDocument();
    expect(screen.queryByText('蔡何')).not.toBeInTheDocument();
    expect(screen.getByText(/Executor: OFFLINE/)).toBeInTheDocument();

    await act(async () => {
      await user.click(screen.getByRole('tab', { name: '真人参与者' }));
    });

    expect(await screen.findByText('蔡何')).toBeInTheDocument();
    expect(screen.getByText('工号: 10000 · 真人')).toBeInTheDocument();
    expect(screen.getByLabelText('真人参与者头像')).toHaveStyle({
      backgroundColor: '#fff7e6',
      color: '#fa8c16',
      border: '1px solid #ffd591',
    });
    expect(screen.queryByText('Agent Dev')).not.toBeInTheDocument();
    expect(screen.queryByText('离线')).not.toBeInTheDocument();
    expect(screen.queryByText(/Executor:/)).not.toBeInTheDocument();
  });
});
