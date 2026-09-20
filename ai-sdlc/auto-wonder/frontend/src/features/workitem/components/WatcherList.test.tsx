import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { WatcherList } from './WatcherList';
import { useWatchers } from '../hooks';
import type { Participant } from '@/shared/types/workitem';

vi.mock('../hooks', () => ({
  useWatchers: vi.fn(),
}));

const mockedUseWatchers = vi.mocked(useWatchers);

function watchers(list: Participant[], isLoading = false) {
  // useWatchers 返回 react-query 结果，这里只提供组件用到的字段
  mockedUseWatchers.mockReturnValue({ data: list, isLoading } as unknown as ReturnType<typeof useWatchers>);
}

describe('WatcherList', () => {
  beforeEach(() => {
    mockedUseWatchers.mockReset();
  });

  it('renders each watcher with a count in the card title', () => {
    watchers([
      { userId: 100, name: '蔡何', displayId: '10000', role: 'HUMAN', roleName: '关注人', isAgent: false, online: false, status: '0' },
      { userId: 101, name: '张三', displayId: '10001', role: 'HUMAN', roleName: '关注人', isAgent: false, online: false, status: '0' },
    ]);

    render(<WatcherList workitemId="42" />);

    expect(screen.getByTestId('workitem-watcher-list')).toBeInTheDocument();
    expect(screen.getByText('关注人 (2)')).toBeInTheDocument();
    expect(screen.getByText('蔡何')).toBeInTheDocument();
    expect(screen.getByText('张三')).toBeInTheDocument();
    expect(screen.getByText('工号: 10000')).toBeInTheDocument();
  });

  it('shows an empty state when nobody is watching', () => {
    watchers([]);

    render(<WatcherList workitemId="42" />);

    expect(screen.getByText('关注人')).toBeInTheDocument();
    expect(screen.getByText('暂无关注人')).toBeInTheDocument();
  });

  it('falls back to userId when displayId is missing', () => {
    watchers([
      { userId: 55, name: '李四', role: 'HUMAN', roleName: '关注人', isAgent: false, online: false, status: '0' },
    ]);

    render(<WatcherList workitemId="42" />);

    expect(screen.getByText('工号: 55')).toBeInTheDocument();
  });
});
