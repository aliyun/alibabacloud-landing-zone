import { render, screen, fireEvent } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { DispatchDiagnostics } from './DispatchDiagnostics';

const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', data });

it('loads diagnostics on demand and pages original content in English', async () => {
  await i18n.changeLanguage('en-US');
  let lists = 0;
  server.use(
    http.get('/api/dispatches/44/diagnostics', () => { lists++; return ok([{ id: 1, name: 'receipts/original.json', sizeBytes: 1000001 }]); }),
    http.get('/api/dispatches/44/diagnostics/1', ({ request }) => ok(new URL(request.url).searchParams.get('offset') === '5'
      ? { text: '原始错误', nextOffset: null, truncated: false }
      : { text: 'first', nextOffset: 5, truncated: true })),
  );
  render(<DispatchDiagnostics dispatchId={44} />);
  expect(lists).toBe(0);
  fireEvent.click(screen.getByText('Diagnostic attachments'));
  fireEvent.click(await screen.findByText('View content'));
  expect(await screen.findByText('first')).toBeInTheDocument();
  fireEvent.click(screen.getByText('Next page'));
  expect(await screen.findByText('原始错误')).toBeInTheDocument();
  expect(screen.queryByText('Next page')).not.toBeInTheDocument();
  await i18n.changeLanguage('zh-CN');
});

it('explains missing diagnostics for old executors', async () => {
  await i18n.changeLanguage('zh-CN');
  server.use(http.get('/api/dispatches/44/diagnostics', () => ok([])));
  render(<DispatchDiagnostics dispatchId={44} />);
  fireEvent.click(screen.getByText('诊断附件'));
  expect(await screen.findByText('尚无诊断附件。旧版执行器可能不支持自动采集。')).toBeInTheDocument();
});
