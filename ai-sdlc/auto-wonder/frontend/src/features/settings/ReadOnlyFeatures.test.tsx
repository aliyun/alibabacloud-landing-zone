import { useState } from 'react';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { i18n } from '@/shared/i18n';
import type { WorkspaceDomain } from '@/shared/types/common';
import { ReadOnlyFeatures } from './ReadOnlyFeatures';

function Editor({ initial = [] }: { initial?: WorkspaceDomain[] }) {
  const [value, setValue] = useState(initial);
  return <><ReadOnlyFeatures value={value} onChange={setValue} id="permissions" /><output data-testid="value">{JSON.stringify(value)}</output></>;
}

describe('ReadOnlyFeatures', () => {
  it('keeps all domain groups collapsed by default', () => {
    render(<Editor initial={['SDLC', 'AI', 'AUTH']} />);
    expect(screen.getByRole('button', { name: /^交付与工单协作/ })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByRole('button', { name: /^数字员工/ })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByRole('button', { name: /^流程与工作空间配置/ })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('radio', { name: '读写' })).not.toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'SDLC 流程' })).not.toBeInTheDocument();
    expect(screen.getByTestId('value')).toHaveTextContent('["SDLC","AI","AUTH"]');
  });

  it('sets a collapsed group at once, then lets one feature differ without changing other groups', async () => {
    const user = userEvent.setup();
    render(<Editor initial={['AUTH', 'AI', 'SDLC']} />);
    const workers = screen.getByRole('region', { name: '数字员工' });
    const bulkReadOnly = within(workers).getByRole('button', { name: '将数字员工全部设为只读' });
    expect(bulkReadOnly).toHaveTextContent('全组只读');
    expect(bulkReadOnly).toHaveAttribute('aria-pressed', 'false');
    await user.click(bulkReadOnly);
    expect(bulkReadOnly).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('value')).toHaveTextContent('["AUTH","AI","SDLC","AGENT","SQUAD","EXECUTOR"]');
    expect(within(workers).getByRole('button', { name: /^数字员工/ })).toHaveAttribute('aria-expanded', 'false');
    await user.click(within(workers).getByRole('button', { name: /^数字员工/ }));
    const agent = within(workers).getByRole('group', { name: '数字员工 · 员工管理' });
    await user.click(within(agent).getByRole('radio', { name: '读写' }));
    expect(bulkReadOnly).toHaveAttribute('aria-pressed', 'false');
    expect(within(agent).getByRole('radio', { name: '读写' })).toBeChecked();
    expect(within(workers).getByRole('button', { name: /^数字员工/ })).toHaveTextContent('2 项只读 · 1 项读写');
    expect(screen.getByTestId('value')).toHaveTextContent('["AUTH","AI","SDLC","SQUAD","EXECUTOR"]');
    const bulkReadWrite = within(workers).getByRole('button', { name: '将数字员工全部设为读写' });
    await user.click(bulkReadWrite);
    expect(bulkReadWrite).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('value')).toHaveTextContent('["AUTH","AI","SDLC"]');
  });

  it('preserves hidden and legacy permissions across language and group changes', async () => {
    const user = userEvent.setup();
    render(<Editor initial={['MCP', 'SDLC', 'AI']} />);
    await act(async () => { await i18n.changeLanguage('en-US'); });
    try {
      const advanced = screen.getByRole('region', { name: 'Other features (hidden and experimental)' });
      await user.click(within(advanced).getByRole('button', { name: /^Other features/ }));
      expect(within(advanced).getAllByRole('radio', { name: 'Read only' })).toHaveLength(4);
      await user.click(within(advanced).getByRole('button', { name: 'Set all Other features (hidden and experimental) features to Read/write' }));
      expect(screen.getByTestId('value')).toHaveTextContent('["MCP","SDLC"]');
      const configuration = screen.getByRole('region', { name: 'Workflow and workspace configuration' });
      await user.click(within(configuration).getByRole('button', { name: /^Workflow and workspace configuration/ }));
      expect(within(within(configuration).getByRole('group', { name: 'SDLC workflows' })).getByRole('radio', { name: 'Read only' })).toBeChecked();
      expect(screen.getByTestId('value')).toHaveTextContent('["MCP","SDLC"]');
    } finally { await act(async () => { await i18n.changeLanguage('zh-CN'); }); }
  });

  it('allows viewing groups but disables all permission changes in view mode', async () => {
    const user = userEvent.setup();
    render(<ReadOnlyFeatures value={['SDLC']} disabled />);
    const configuration = screen.getByRole('region', { name: '流程与工作空间配置' });
    await user.click(within(configuration).getByRole('button', { name: /^流程与工作空间配置/ }));
    const sdlc = within(configuration).getByRole('group', { name: 'SDLC 流程' });
    expect(within(sdlc).getByRole('radio', { name: '只读' })).toBeChecked();
    for (const radio of within(configuration).getAllByRole('radio')) expect(radio).toBeDisabled();
    for (const button of within(configuration).getAllByRole('button', { name: /全部设为/ })) expect(button).toBeDisabled();
  });
});
