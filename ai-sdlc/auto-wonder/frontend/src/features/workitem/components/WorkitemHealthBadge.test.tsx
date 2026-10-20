import { i18n } from '@/shared/i18n';
import userEvent from '@testing-library/user-event';
import { describe, it, expect } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { WorkitemHealthBadge } from './WorkitemHealthBadge';

describe('WorkitemHealthBadge', () => {
  it('renders warning tag when health is STUCK', () => {
    render(<WorkitemHealthBadge item={{ health: 'STUCK', healthReason: '执行超时' }} />);
    expect(screen.getByText('异常')).toBeInTheDocument();
  });

  it('renders nothing when health is OK', () => {
    const { container } = render(<WorkitemHealthBadge item={{ health: 'OK', healthReason: null }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when health is undefined', () => {
    const { container } = render(<WorkitemHealthBadge item={{}} />);
    expect(container).toBeEmptyDOMElement();
  });
});

it('updates a held terminal health tooltip from finite status while unknown reasons remain raw', async () => {
  const item = { health: 'STUCK' as const, healthReason: '最近一次执行超时，流程已停止且无自动恢复，请人工介入', executionStatus: 'TIMEOUT' };
  render(<WorkitemHealthBadge item={item} />);
  await act(async () => { await i18n.changeLanguage('en-US'); });
  await userEvent.hover(screen.getByText('Stuck'));
  expect(await screen.findByRole('tooltip')).toHaveTextContent('The latest execution timed out. The workflow has stopped without automatic recovery; intervene manually.');
  expect(screen.queryByRole('img', { name: 'warning' })).not.toBeInTheDocument();
  await act(async () => { await i18n.changeLanguage('zh-CN'); });
});
