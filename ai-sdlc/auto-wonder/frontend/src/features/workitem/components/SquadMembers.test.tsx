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
    expect(screen.getByRole('tab', { name: '数字员工成员' })).toBeInTheDocument();
    expect(screen.getByText('Agent Dev')).toBeInTheDocument();
    expect(screen.queryByText('蔡何')).not.toBeInTheDocument();
    expect(screen.getByText(/Executor: 离线/)).toBeInTheDocument();

    await act(async () => {
      await user.click(screen.getByRole('tab', { name: '真人参与者' }));
    });

    expect(await screen.findByText('蔡何')).toBeInTheDocument();
    expect(screen.getByText('工号: 10000 · 真人')).toBeInTheDocument();
    expect(screen.getByLabelText('真人参与者头像')).toContainElement(screen.getByLabelText('user'));
    expect(screen.queryByText('Agent Dev')).not.toBeInTheDocument();
    expect(screen.queryByText('离线')).not.toBeInTheDocument();
    expect(screen.queryByText(/Executor:/)).not.toBeInTheDocument();
  });
});

it('switches coded participant identities and runtime statuses, preserving unknown role text and authored names', async () => {
  const { i18n } = await import('@/shared/i18n');
  const participants: Participant[] = [
    { userId: 1, name: '<em>员工</em>', role: 'AGENT', roleName: '开发小队成员', isAgent: true, online: false, status: 'PENDING_REVIEW', executorStatus: 'OFFLINE' },
    { userId: 2, name: '<b>自定义</b>', targetType: 'CUSTOM', role: 'CUSTOM', roleName: '<em>我的身份</em>', isAgent: true, online: true, status: 'constructor', executorStatus: 'CUSTOM_STATE' },
    { userId: 3, name: '用户原名', role: 'HUMAN', roleName: '真人', isAgent: false, online: false },
  ];
  const { container } = render(<SquadMembers participants={participants} />);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  try {
    expect(screen.getByText('ID: 1 · Squad member')).toBeInTheDocument();
    expect(screen.getByText('Agent status: Pending review · Executor: Offline')).toBeInTheDocument();
    expect(screen.getByText('ID: 2 · <em>我的身份</em>')).toBeInTheDocument();
    expect(screen.getByText('CUSTOM_STATE', { selector: '.ant-tag' })).toBeInTheDocument();
    expect(screen.getByText('Agent status: constructor · Executor: CUSTOM_STATE')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'Human participants' }));
    expect(screen.getByText('ID: 3 · Human')).toBeInTheDocument();
    expect(screen.getByText('用户原名')).toBeInTheDocument();
    expect(container.querySelector('em')).toBeNull();
    await act(async () => { await i18n.changeLanguage('zh-CN'); });
    expect(screen.getByText('工号: 3 · 真人')).toBeInTheDocument();
  } finally { await act(async () => { await i18n.changeLanguage('zh-CN'); }); }
});
