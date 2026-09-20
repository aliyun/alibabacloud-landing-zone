import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WorkitemHeader } from './WorkitemHeader';

function renderHeader(props: Partial<React.ComponentProps<typeof WorkitemHeader>> = {}) {
  // 分享按钮内部走 useQuery 取平台品牌，头部因此必须在 QueryClient 里渲染；
  // 每次新建 client，避免多个用例共用缓存互相污染。
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <WorkitemHeader title="工单标题" statusName="开发中" workType="REQ" {...props} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WorkitemHeader', () => {
  it('renders title, status and type', () => {
    renderHeader();

    expect(screen.getByRole('heading', { name: '工单标题' })).toBeInTheDocument();
    expect(screen.getByText('开发中')).toBeInTheDocument();
    expect(screen.getByText('需求')).toBeInTheDocument();
  });

  it('shows the accumulated workitem credits at the top', () => {
    renderHeader({
      usage: {
        credits: 70,
        runs: [{ agentId: 40, agentName: 'DEV', runIndex: 1, label: 'DEV run-1', credits: 70 }],
      },
    });

    expect(screen.getByTestId('workitem-credits-badge')).toHaveTextContent('70.00 Credits');
  });

  it('hides the credits badge when the workitem has no usage', () => {
    renderHeader({ usage: null });
    expect(screen.queryByTestId('workitem-credits-badge')).not.toBeInTheDocument();

    renderHeader({ usage: { credits: 0 } });
    expect(screen.queryByTestId('workitem-credits-badge')).not.toBeInTheDocument();

    renderHeader();
    expect(screen.queryByTestId('workitem-credits-badge')).not.toBeInTheDocument();
  });

  it('renders the share entry once the workitem id is known', () => {
    renderHeader({ workitemId: 54843 });

    expect(screen.getByTestId('workitem-share-button')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '分享' })).toBeInTheDocument();
  });

  it('renders no share entry without a workitem id to link to', () => {
    renderHeader();

    expect(screen.queryByTestId('workitem-share-button')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '分享' })).not.toBeInTheDocument();
  });
});
