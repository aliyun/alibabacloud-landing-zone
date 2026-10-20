import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { message } from 'antd';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/mocks/server';
import { i18n } from '@/shared/i18n';
import { SettingsPage } from './SettingsPage';
import { MemberRoleSettingsPage } from './MemberRoleSettingsPage';
import { mockRoles } from '@/test/mocks/workspaceRoles';
import { MemberAccessModal } from './MemberAccessModal';
import { ProjectBackupsPage } from './ProjectBackupsPage';
import { useAuthStore } from '@/shared/auth/store';
const ok = (data: unknown) => HttpResponse.json({ success: true, code: '0', message: '', data });
async function locale(value: 'zh-CN' | 'en-US') {
    await act(async () => {
        await i18n.changeLanguage(value);
    });
}
function localeUser() {
    const user = userEvent.setup();
    return {
        click: (element: Element) => act(async () => {
            await user.click(element);
        }),
        type: (element: Element, text: string) => act(async () => {
            await user.type(element, text);
        }),
        clear: (element: Element) => act(async () => {
            await user.clear(element);
        }),
        keyboard: (text: string) => act(async () => {
            await user.keyboard(text);
        }),
    };
}
function mount(element: React.ReactElement) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
    const router = createMemoryRouter([{ path: '/settings', element }], { initialEntries: ['/settings?keep=raw'] });
    return { ...render(<QueryClientProvider client={client}><RouterProvider router={router}/></QueryClientProvider>), router, client };
}
const member = { userId: 2, username: 'dev', nickname: '<em>开发者</em>', email: 'dev@co.com', owner: false, accessLevel: 'READ_WRITE' as const, identityTags: ['原文'], readOnlyDomains: [], roleId: 12, roleCode: 'READ_WRITE', roleName: null, joinedAt: '2026-07-05' };
beforeEach(async () => {
    await locale('zh-CN');
    server.use(http.get('/api/workspaces/current/roles', () => ok(mockRoles)));
    useAuthStore.getState().clear();
    useAuthStore.getState().setCurrentWorkspace({ id: 1, name: 'O', description: '' }, 'ADMIN');
});
afterEach(async () => {
    cleanup();
    message.destroy();
    vi.restoreAllMocks();
    await locale('zh-CN');
});
it('updates cached settings and password names without resetting drafts or refetching, and saves exact replacement bytes', async () => {
    const user = localeUser();
    let reads = 0;
    let body: unknown;
    let quota: unknown;
    let finish!: () => void;
    const pending = new Promise<void>(resolve => {
        finish = resolve;
    });
    const success = vi.spyOn(message, 'success');
    server.use(http.get('/api/ai-usage/quota', () => ok({ maxCalls: 20000, maxTokens: null, concurrencyLimit: 8 })), http.get('/api/ai-usage', () => ok([{ scene: '<b>仓库扫描</b>', period: '原始', callCount: 1200, inputTokens: 3, outputTokens: 0 }])), http.get('/api/settings/AI', () => {
        reads++;
        return ok([{ key: 'default_model', valueJson: 'qwen-max' }, { key: 'api_key', valueJson: '********', secret: true }]);
    }), http.put('/api/ai-usage/quota', async ({ request }) => {
        quota = await request.json();
        return ok(null);
    }), http.put('/api/settings/AI', async ({ request }) => {
        body = await request.json();
        await pending;
        return ok(null);
    }));
    const { router, container } = mount(<SettingsPage />);
    const model = await screen.findByLabelText('默认模型');
    const secret = screen.getByLabelText('API Key');
    const calls = screen.getByLabelText('月调用次数');
    const tokens = screen.getByLabelText('月 Token 上限');
    const concurrency = screen.getByLabelText('并发限制');
    await waitFor(() => expect(concurrency).toHaveValue('8'));
    await user.clear(concurrency);
    await waitFor(() => expect(concurrency).toHaveValue(''));
    await user.type(concurrency, '0');
    concurrency.focus();
    await user.keyboard('{ArrowDown}');
    expect(concurrency).toHaveValue('0');
    await user.keyboard('{ArrowUp}');
    expect(concurrency).toHaveValue('1');
    await user.clear(tokens);
    await user.clear(model);
    await user.type(model, '原始模型');
    await user.clear(secret);
    await user.type(secret, 'synthetic-原文');
    await user.clear(calls);
    await user.type(calls, '30000');
    await locale('en-US');
    expect(screen.getByRole('heading', { name: 'Settings' })).toBeInTheDocument();
    expect(screen.getByLabelText('Default model')).toBe(model);
    expect(model).toHaveValue('原始模型');
    expect(secret).toHaveValue('synthetic-原文');
    expect(calls).toHaveValue('30000');
    expect(reads).toBe(1);
    const show = screen.getByRole('button', { name: 'Show secret' });
    show.focus();
    await user.keyboard('{Enter}');
    expect(secret).toHaveAttribute('type', 'text');
    await locale('zh-CN');
    expect(screen.getByRole('button', { name: '隐藏密钥' })).toBeInTheDocument();
    expect(secret).toHaveAttribute('type', 'text');
    expect(router.state.location.search).toBe('?keep=raw');
    expect(container.querySelector('b')).toBeNull();
    expect(screen.getByText('<b>仓库扫描</b>')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '保存AI配置' }));
    await waitFor(() => expect(body).toBeDefined());
    await locale('en-US');
    expect(screen.getByRole('button', { name: 'Save AI settings' })).toHaveClass('ant-btn-loading');
    await act(async () => {
        finish();
    });
    await waitFor(() => expect(success).toHaveBeenCalledWith('AI settings saved'));
    expect(quota).toEqual({ maxCalls: 30000, maxTokens: null, concurrencyLimit: 1 });
    expect(body).toEqual({ items: [{ key: 'default_model', valueJson: '原始模型', secret: false }, { key: 'request_timeout_seconds', valueJson: '', secret: false }, { key: 'api_key', valueJson: 'synthetic-原文', secret: true }] });
});
it.each(['DINGTALK', 'FEISHU'])('keeps %s notification/system drafts and canonical preference keys across switches', async (provider) => {
    const user = localeUser();
    let reads = 0;
    let body!: { items: import('./api').NotifyPrefItem[] };
    const key = provider === 'FEISHU' ? 'feishu' : 'dingtalk';
    server.use(http.get('/api/ai-usage/quota', () => ok({})), http.get('/api/ai-usage', () => ok([])), http.get('/api/settings/AI', () => ok([])), http.get('/api/platform/im-channels', () => ok([{ provider, selected: true, enabled: false }])), http.get('/api/notifications/prefs', () => ok([])), http.get('/api/settings/NOTIFY', () => {
        reads++;
        return ok([{ key: `${key}_enabled`, valueJson: 'true' }]);
    }), http.get('/api/settings/SYSTEM', () => {
        reads++;
        return ok([{ key: 'artifact_bucket', valueJson: '原文' }]);
    }), http.put('/api/settings/NOTIFY', () => ok(null)), http.put('/api/notifications/prefs', async ({ request }) => {
        body = await request.json() as { items: import('./api').NotifyPrefItem[] };
        return ok(null);
    }));
    mount(<SettingsPage />);
    await user.click(screen.getByRole('tab', { name: '通知配置' }));
    const enabled = await screen.findByLabelText(provider === 'FEISHU' ? '启用飞书通知' : '启用钉钉通知');
    await user.clear(enabled);
    await user.type(enabled, 'false');
    await locale('en-US');
    expect(screen.getByLabelText(provider === 'FEISHU' ? 'Enable Feishu notifications' : 'Enable DingTalk notifications')).toBe(enabled);
    expect(enabled).toHaveValue('false');
    const toggle = screen.getByRole('switch', { name: 'Work item assigned: In-app' });
    await user.click(toggle);
    await locale('zh-CN');
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(enabled).toHaveValue('false');
    expect(reads).toBe(1);
    await user.click(screen.getByRole('button', { name: '保存通知配置' }));
    await waitFor(() => expect(body).toBeDefined());
    expect(body.items[0]).toEqual({ type: 'WORKITEM_ASSIGNED', inApp: false, dingtalk: false, feishu: false });
    await user.click(screen.getByRole('tab', { name: '系统配置' }));
    const bucket = await screen.findByLabelText('产物 Bucket');
    await user.type(bucket, '-draft');
    const before = reads;
    await locale('en-US');
    expect(screen.getByLabelText('Artifact Bucket')).toBe(bucket);
    expect(bucket).toHaveValue('原文-draft');
    await locale('zh-CN');
    expect(bucket).toHaveValue('原文-draft');
    expect(reads).toBe(before);
});
it('localizes an independent member editor and held validation while preserving raw tags and access selection', async () => {
    const user = localeUser();
    const confirm = vi.fn();
    mount(<MemberAccessModal roles={mockRoles} open member={{ ...member, identityTags: Array.from({ length: 8 }, (_, i) => `原文${i}`) }} loading={false} onClose={() => { }} onConfirm={confirm}/>);
    const tags = screen.getByRole('combobox', { name: '身份标签' });
    await user.type(tags, '<b>原文</b>{enter}');
    await user.click(screen.getByRole('combobox', { name: '角色' }));
    await user.click(screen.getByText('交付协作 / Delivery'));
    await user.click(screen.getByRole('button', { name: /保\s*存/ }));
    await screen.findByText('身份标签最多 8 项');
    await locale('en-US');
    expect(await screen.findByText('Use at most 8 identity tags')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Identity tags' })).toBe(tags);
    expect(screen.getByRole('combobox', { name: 'Role' }).closest('.ant-select')).toHaveTextContent('交付协作 / Delivery');
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
    expect(screen.getAllByText('<b>原文</b>')[0]).toBeInTheDocument();
    await locale('zh-CN');
    expect(await screen.findByText('身份标签最多 8 项')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '关闭' })).toBeInTheDocument();
    expect(confirm).not.toHaveBeenCalled();
});
it('preserves member filters, owner selection and rejection draft while localizing portals and unknown codes safely', async () => {
    const user = localeUser();
    let reads = 0;
    let candidateReads = 0;
    let rejected: unknown;
    let finish!: () => void;
    const pending = new Promise<void>(resolve => {
        finish = resolve;
    });
    const success = vi.spyOn(message, 'success');
    server.use(http.get('/api/workspaces/current/members', () => {
        reads++;
        return ok([member]);
    }), http.get('/api/workspaces/current/membership', () => ok({ ...member, accessLevel: 'ADMIN' })), http.get('/api/workspaces/current/member-candidates', () => {
        candidateReads++;
        return ok([{ userId: 3, nickname: '<b>候选人</b>', username: 'raw', email: 'new@co.com' }]);
    }), http.get('/api/workspaces/current/access-requests', () => {
        reads++;
        return ok([{ id: 8, requesterId: 2, requesterName: '<em>申请人</em>', requestedLevel: 'constructor', status: 'PENDING' }]);
    }), http.post('/api/workspaces/current/access-requests/8/reject', async ({ request }) => {
        rejected = await request.json();
        await pending;
        return ok(null);
    }));
    const { router } = mount(<MemberRoleSettingsPage />);
    await screen.findByText('dev@co.com');
    await waitFor(() => expect(screen.getByRole('combobox', { name: '搜索全局人员' })).toBeEnabled());
    await user.type(screen.getByRole('combobox', { name: '搜索全局人员' }), '原文');
    await user.click(await screen.findByText('<b>候选人</b> (new@co.com)'));
    const candidateBefore = candidateReads;
    await user.click(screen.getByRole('combobox', { name: '按角色筛选' }));
    await user.click(screen.getAllByText('读写者').find((element) => element.classList.contains('ant-select-item-option-content'))!);
    await waitFor(() => expect(screen.getByRole('button', { name: '移交 Owner' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: '移交 Owner' }));
    const target = screen.getByRole('combobox', { name: '目标成员' });
    await user.click(target);
    await user.click(await screen.findByText('<em>开发者</em> (dev@co.com)'));
    await locale('en-US');
    expect(screen.getByRole('dialog', { name: 'Transfer workspace Owner' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Target member' })).toBe(target);
    expect(screen.getByRole('button', { name: 'Confirm transfer' })).toBeEnabled();
    expect(reads).toBe(1);
    expect(candidateReads).toBe(candidateBefore);
    expect(screen.getByRole('combobox', { name: 'Filter by role' }).closest('.ant-select')).toHaveTextContent('Collaborator');
    expect(screen.getByRole('button', { name: 'Add member' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await user.click(screen.getByRole('tab', { name: 'Access requests' }));
    await screen.findByText('<em>申请人</em>');
    expect(screen.getByText('constructor')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reject' }));
    const reason = screen.getByRole('textbox', { name: 'Rejection reason (optional)' });
    await user.type(reason, '<b>拒绝原文</b>');
    await locale('zh-CN');
    expect(screen.getByRole('textbox', { name: '拒绝原因（可选）' })).toBe(reason);
    expect(reason).toHaveValue('<b>拒绝原文</b>');
    expect(screen.getByText('拒绝 <em>申请人</em> 的申请')).toBeInTheDocument();
    expect(reads).toBe(2);
    expect(router.state.location.search).toBe('?keep=raw&tab=requests');
    await user.click(screen.getByRole('button', { name: '确认拒绝' }));
    await waitFor(() => expect(rejected).toEqual({ reason: '<b>拒绝原文</b>' }));
    await locale('en-US');
    expect(screen.getByRole('button', { name: 'Confirm rejection' })).toHaveClass('ant-btn-loading');
    await act(async () => {
        finish();
    });
    await waitFor(() => expect(success).toHaveBeenCalledWith('Request rejected'));
});
it('updates backups without refetching, preserves raw metadata and requests a fresh signed URL', async () => {
    const user = localeUser();
    let reads = 0;
    let signs = 0;
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => { });
    server.use(http.get('/api/workspaces/current/backups', () => {
        reads++;
        return ok([{ id: 'raw-id', status: 'SUCCEEDED', createdAt: '2026-08-06T14:43:07Z', ossRef: '<b>存储</b>', sizeBytes: 1264128 }, { id: 'failure', status: 'FAILED', createdAt: 'raw-invalid', errorMessage: '原始错误' }, { id: 'unknown', status: 'constructor', createdAt: '' }]);
    }), http.get('/api/workspaces/current/backups/raw-id/download', () => {
        signs++;
        return ok({ url: 'https://example.com/raw.zip' });
    }));
    mount(<ProjectBackupsPage />);
    await screen.findByText('<b>存储</b>');
    await locale('en-US');
    expect(screen.getByRole('heading', { name: 'Project configuration backups' })).toBeInTheDocument();
    expect(screen.getByText('Succeeded')).toBeInTheDocument();
    expect(screen.getByText('1,234.5 KiB')).toBeInTheDocument();
    expect(screen.getByText(new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date('2026-08-06T14:43:07Z')))).toBeInTheDocument();
    expect(screen.getByText('raw-invalid')).toBeInTheDocument();
    expect(screen.getByText('constructor')).toBeInTheDocument();
    expect(screen.getByText('原始错误')).toBeInTheDocument();
    expect(reads).toBe(1);
    expect(signs).toBe(0);
    await user.click(screen.getAllByRole('button', { name: 'Download' })[0]);
    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    expect(signs).toBe(1);
    expect(screen.getAllByRole('button', { name: 'Download' })[1]).toBeDisabled();
    await locale('zh-CN');
    expect(screen.getByText('成功')).toBeInTheDocument();
    expect(reads).toBe(1);
});
it.each([[0, '0 members'], [1, '1 member'], [2, '2 members']] as const)('uses a numeric plural count for %s members', async (count, total) => {
    server.use(http.get('/api/workspaces/current/members', () => ok(Array.from({ length: count }, (_, i) => ({ ...member, userId: i + 1, email: `user${i}@co.com` })))), http.get('/api/workspaces/current/membership', () => ok({ ...member, accessLevel: 'ADMIN' })));
    await locale('en-US');
    mount(<MemberRoleSettingsPage />);
    if (count > 0)
        expect(await screen.findByText(total)).toBeInTheDocument();
    else
        expect(i18n.t('settings:totalMembers', { count, total: '0' })).toBe('0 members');
    expect(screen.getByRole('combobox', { name: 'Filter by role' }).closest('.ant-select')).toHaveTextContent(`All (${count})`);
});
it('uses completion-time backup feedback and preserves the create request body', async () => {
    let finish!: () => void;
    let body: unknown;
    const pending = new Promise<void>(resolve => {
        finish = resolve;
    });
    const success = vi.spyOn(message, 'success');
    server.use(http.get('/api/workspaces/current/backups', () => ok([])), http.post('/api/workspaces/current/backups', async ({ request }) => {
        body = await request.json();
        await pending;
        return ok({ id: 'raw' });
    }));
    mount(<ProjectBackupsPage />);
    const user = localeUser();
    await user.click(screen.getByRole('button', { name: '立即备份到云端' }));
    await waitFor(() => expect(body).toEqual({}));
    await locale('en-US');
    expect(screen.getByRole('button', { name: 'Back up to cloud now' })).toHaveClass('ant-btn-loading');
    await act(async () => {
        finish();
    });
    await waitFor(() => expect(success).toHaveBeenCalledWith('Project configuration backed up to object storage'));
});
