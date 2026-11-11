import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { PrdConversation } from './PrdConversation';
import { File as UploadFile } from 'node:buffer';

vi.mock('@/features/workitem/clarification/SquadAgentSelector', () => ({ SquadAgentSelector: () => null }));
vi.mock('./PrdBrowserSettings', () => ({
  browserSelectionValid: (value: unknown) => !!value,
  PrdBrowserSettings: ({ onChange }: { onChange: (value: unknown) => void }) => <button onClick={() => onChange({ codeOnly: true })}>Code only</button>,
}));
const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', data });

describe('PrdConversation API routing', () => {
  it('uploads pasted screenshots as multipart and attributes shared messages to their authors', async () => {
    let uploaded = false;
    let submitted: unknown;
    server.use(
      http.get('/api/workitems/10/prd/conversation', () => ok({ id: 50, participants: { 7: 'Alice', 8: 'Bob' }, turns: [
        { id: 1, direction: 'IN', content: 'internal prompt', sourceContext: '{"userMessage":"First idea"}', submittedUserId: 7, status: 'COMPLETED' },
        { id: 2, direction: 'IN', content: 'internal prompt', sourceContext: '{"userMessage":"Another idea"}', submittedUserId: 8, status: 'COMPLETED' },
      ] })),
      http.get('/api/workitems/10/prd/conversation/50/events', () => ok([])),
      http.post('/api/workitems/10/prd/conversation/prepare', () => ok({ id: 50, turns: [] })),
      http.post('/api/workitems/10/prd/conversation/50/attachments', async ({ request }) => {
        expect(request.headers.get('content-type')).toContain('multipart/form-data');
        const form = await request.formData();
        const file = form.get('file');
        expect(file).toBeInstanceOf(UploadFile);
        const uploadedFile = file as File;
        expect(uploadedFile.name).toBe('screen.png');
        expect(uploadedFile.type).toBe('image/png');
        expect(Array.from(new Uint8Array(await uploadedFile.arrayBuffer()))).toEqual([105, 109, 97, 103, 101]);
        uploaded = true;
        return ok({ id: 90 });
      }),
      http.post('/api/workitems/10/prd/conversation', async ({ request }) => { submitted = await request.json(); return ok(null); }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = render(<MemoryRouter initialEntries={['/?prdAgent=42&prdSquad=7']}>
      <QueryClientProvider client={client}><PrdConversation workitemId="10" /></QueryClientProvider>
    </MemoryRouter>);
    expect(await screen.findByText('Alice')).toBeInTheDocument();
    expect(screen.getByText('Bob')).toBeInTheDocument();
    expect(screen.queryByText('internal prompt')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Code only' }));
    fireEvent.paste(screen.getByRole('textbox'), { clipboardData: { files: [new UploadFile(['image'], 'screen.png', { type: 'image/png' })] } });
    await userEvent.click(screen.getByRole('button', { name: /发\s*送/ }));
    await waitFor(() => expect(submitted).toMatchObject({ agentId: 42, content: '', attachmentIds: [90] }));
    expect(uploaded).toBe(true);
    view.unmount(); client.clear();
  });
  it('attaches the picked prototype element to the next design message', async () => {
    let submitted: unknown;
    const cleared = vi.fn();
    server.use(
      http.get('/api/workitems/11/prd/conversation', () => ok({ id: 60, turns: [
        { id: 1, direction: 'IN', content: 'internal prompt', status: 'COMPLETED', submittedUserId: 7,
          sourceContext: '{"userMessage":"Round the corners","element":{"tag":"button","selector":"#save","cssPath":"body > button","xpath":"/x","text":"Save"}}' },
      ] })),
      http.get('/api/workitems/11/prd/conversation/60/events', () => ok([])),
      http.post('/api/workitems/11/prd/conversation', async ({ request }) => { submitted = await request.json(); return ok(null); }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = render(<MemoryRouter initialEntries={['/?prdAgent=42&prdSquad=7']}>
      <QueryClientProvider client={client}><PrdConversation workitemId="11" onClearElement={cleared} element={{
        tag: 'button', selector: '#save', cssPath: 'body > button', xpath: '/html[1]/body[1]/button[1]', text: 'Save changes', page: 'Home',
      }} /></QueryClientProvider>
    </MemoryRouter>);
    expect(await screen.findByText('Round the corners')).toBeInTheDocument();
    expect(screen.getAllByText('已选中：Save').length).toBeGreaterThan(0);
    expect(screen.getByText('已选中：Save changes')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Code only' }));
    await userEvent.type(screen.getByRole('textbox', { name: '设计对话' }), 'Make the button blue');
    await userEvent.click(screen.getByRole('button', { name: /发\s*送/ }));
    await waitFor(() => expect(submitted).toMatchObject({ content: 'Make the button blue',
      element: { tag: 'button', selector: '#save', text: 'Save changes' } }));
    expect(cleared).toHaveBeenCalledOnce();
    view.unmount(); client.clear();
  });
  it('loads history, sends and saves through the /api workitem routes', async () => {
    let submitted: unknown;
    const saved = vi.fn();
    const onDraftChange = vi.fn();
    const onPreview = vi.fn();
    server.use(
      http.get('/api/workitems/39089/prd/conversation', ({ request }) => {
        expect(new URL(request.url).searchParams.get('agentId')).toBe('42');
        return ok({ id: 50, turns: [{ id: 60, direction: 'OUT', content: JSON.stringify({ title: 'Design', changeLog: 'Design response' }), status: 'COMPLETED' }] });
      }),
      http.post('/api/workitems/39089/prd/conversation', async ({ request }) => {
        submitted = await request.json(); return ok(null);
      }),
      http.get('/api/workitems/39089/prd/conversation/50/events', () => ok([])),
      http.post('/api/workitems/39089/prd/conversation/preview', () => ok('<h1>Live design</h1>')),
      http.post('/api/workitems/39089/prd/conversation/50/turns/60/save', () => { saved(); return ok({ id: 70 }); }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = render(<MemoryRouter initialEntries={['/?prdAgent=42&prdSquad=7']}>
      <QueryClientProvider client={client}><PrdConversation workitemId="39089" onDraftChange={onDraftChange} onPreview={onPreview} /></QueryClientProvider>
    </MemoryRouter>);
    expect(await screen.findByText('Design response')).toBeInTheDocument();
    await waitFor(() => expect(onPreview).toHaveBeenCalledWith('<h1>Live design</h1>'));
    expect(saved).not.toHaveBeenCalled();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Code only' }));
    await user.type(screen.getByRole('textbox', { name: '设计对话' }), 'Generate PRD');
    await user.click(screen.getByRole('button', { name: /发\s*送/ }));
    await waitFor(() => expect(submitted).toEqual({ agentId: 42, content: 'Generate PRD', clientMessageId: expect.any(String), attachmentIds: [], browser: { codeOnly: true } }));
    expect(screen.queryByRole('button', { name: '采用当前设计' })).not.toBeInTheDocument();
    await waitFor(() => expect(onDraftChange).toHaveBeenCalledWith(expect.objectContaining({ conversationId: 50, turnId: 60 })));
    expect(saved).not.toHaveBeenCalled();
    view.unmount(); client.clear();
  });
  it('falls back to the element selector when the picked element has no text', async () => {
    server.use(
      http.get('/api/workitems/12/prd/conversation', () => ok({ id: 60, turns: [] })),
      http.get('/api/workitems/12/prd/conversation/60/events', () => ok([])),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = render(<MemoryRouter initialEntries={['/?prdAgent=42&prdSquad=7']}>
      <QueryClientProvider client={client}><PrdConversation workitemId="12" element={{
        tag: 'button', selector: '#save', cssPath: 'body > button', xpath: '/html[1]/body[1]/button[1]',
      }} /></QueryClientProvider>
    </MemoryRouter>);
    expect(await screen.findByText('已选中：#save')).toBeInTheDocument();
    view.unmount(); client.clear();
  });
  it('degrades safely when stored turns carry no or broken source context', async () => {
    server.use(
      http.get('/api/workitems/13/prd/conversation', () => ok({ id: 61, participants: { 7: 'Alice' }, turns: [
        { id: 1, direction: 'IN', content: 'raw first idea', status: 'COMPLETED', submittedUserId: 7 },
        { id: 2, direction: 'IN', content: 'raw second idea', status: 'COMPLETED', submittedUserId: 7, sourceContext: 'not-json' },
      ] })),
      http.get('/api/workitems/13/prd/conversation/61/events', () => ok([])),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = render(<MemoryRouter initialEntries={['/?prdAgent=42&prdSquad=7']}>
      <QueryClientProvider client={client}><PrdConversation workitemId="13" /></QueryClientProvider>
    </MemoryRouter>);
    expect(await screen.findByText('raw first idea')).toBeInTheDocument();
    expect(screen.getByText('raw second idea')).toBeInTheDocument();
    expect(screen.queryByText(/已选中/)).not.toBeInTheDocument();
    view.unmount(); client.clear();
  });
});
