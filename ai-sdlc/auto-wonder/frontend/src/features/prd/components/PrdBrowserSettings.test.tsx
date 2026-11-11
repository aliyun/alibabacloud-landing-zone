import { useState } from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { PrdBrowserSettings, browserSelectionValid, type BrowserSelection } from './PrdBrowserSettings';

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', data });
function Harness({ initial = null }: { initial?: BrowserSelection | null }) {
  const [value, onChange] = useState(initial);
  return <><PrdBrowserSettings workitemId="10" agentId={42} value={value} onChange={onChange} disabled={false} />
    <output data-testid="selection">{JSON.stringify(value)}</output></>;
}
function mount(initial?: BrowserSelection) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><Harness initial={initial} /></QueryClientProvider>);
}
function catalog() {
  server.use(
    http.get('/api/categories', () => ok([{ id: 8, name: 'browser-use', parentId: null }])),
    http.get('/api/skills', () => ok({ list: [{ id: 9, name: 'Team browser', type: 'MCP', categoryId: 8, installSpec: '{"transport":"http"}' }], total: 1 })),
  );
}
describe('PRD browser configuration', () => {
  it('requires an explicit code-only choice when no browser exists', async () => {
    server.use(http.get('/api/categories', () => ok([])), http.get('/api/skills', () => ok({ list: [], total: 0 })));
    mount();
    expect(await screen.findByText(/尚未配置 browser-use/)).toBeInTheDocument();
    expect(screen.getByTestId('selection')).toHaveTextContent('null');
    await userEvent.click(screen.getByRole('checkbox', { name: '仅根据代码设计' }));
    expect(screen.getByTestId('selection')).toHaveTextContent('"codeOnly":true');
    expect(screen.getByText(/本轮不浏览现有界面/)).toBeInTheDocument();
  });
  it('creates via the categorized endpoint and never places the token in selection', async () => {
    catalog(); let created: unknown;
    server.use(http.post('/api/skills/browser', async ({ request }) => { created = await request.json(); return ok({ id: 9 }); }));
    mount(); const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '添加浏览器 MCP' }));
    await user.type(screen.getByLabelText('连接名称'), 'Test browser');
    await user.type(screen.getByLabelText('MCP 服务地址'), 'http://192.168.1.20:8931/mcp');
    await user.type(screen.getByLabelText('Token'), 'test-token');
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(created).toEqual({ name: 'Test browser', url: 'http://192.168.1.20:8931/mcp', token: 'test-token' }));
    await waitFor(() => expect(screen.getByTestId('selection')).toHaveTextContent('"skillId":9'));
    expect(screen.getByTestId('selection')).not.toHaveTextContent('test-token');
  });
  it('checks via the employee executor and clears the result after URL changes', async () => {
    catalog(); let checked: unknown;
    server.use(http.post('/api/workitems/10/prd/conversation/browser-check', async ({ request }) => {
      checked = await request.json();
      return ok({ executorId: 20, result: { success: true, tools: ['browser_navigate', 'browser_snapshot', 'browser_click', 'browser_take_screenshot'].map(name => ({ name })) } });
    }));
    mount({ skillId: 9, url: 'https://example.com', codeOnly: false });
    await userEvent.click(screen.getByRole('button', { name: '通过执行器检查连接' }));
    expect(await screen.findByText(/尚未验证网站登录和人工授权状态/)).toBeInTheDocument();
    expect(checked).toEqual({ agentId: 42, browser: { skillId: 9, url: 'https://example.com', codeOnly: false } });
    await userEvent.type(screen.getByLabelText('应用访问地址（要查看的网站）'), '/settings');
    expect(screen.queryByText(/尚未验证网站登录和人工授权状态/)).not.toBeInTheDocument();
  });
  it('rejects missing selection and credential-bearing URLs', () => {
    expect(browserSelectionValid(null)).toBe(false);
    expect(browserSelectionValid({ codeOnly: true })).toBe(true);
    expect(browserSelectionValid({ codeOnly: false, skillId: 9, url: 'https://user:secret@example.com' })).toBe(false);
  });
});
