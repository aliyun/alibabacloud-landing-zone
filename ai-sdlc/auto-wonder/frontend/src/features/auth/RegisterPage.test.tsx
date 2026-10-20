import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, describe, expect, it } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { i18n } from '@/shared/i18n';
import { MemoryRouter } from 'react-router-dom';
import { RegisterPage } from './RegisterPage';

function renderRegister() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter>
      <RegisterPage />
    </MemoryRouter></QueryClientProvider>,
  );
}

describe('RegisterPage', () => {
  afterEach(async () => { await act(async () => { await i18n.changeLanguage('zh-CN'); }); });
  it('renders registration form inside the product command center landing', () => {
    renderRegister();
    expect(screen.getAllByRole('button', { name: '切换外观' })).toHaveLength(1);
    expect(screen.getByText('AI Native SDLC Platform')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /软件产品\s*自动交付平台/ })).toBeInTheDocument();
    expect(screen.getByText('创建平台账号')).toBeInTheDocument();
    expect(screen.getByLabelText(/用户名/)).toBeInTheDocument();
    expect(screen.getByLabelText(/昵称/)).toBeInTheDocument();
    expect(screen.getByLabelText(/邮箱/)).toBeInTheDocument();
    expect(screen.getByLabelText(/密码/, { selector: 'input' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /注\s*册/ })).toBeInTheDocument();
  });

  it.each(['zh-CN', 'en-US'])('keeps the registration password raw with a keyboard toggle from %s', async locale => {
    await act(async () => { await i18n.changeLanguage(locale); });
    renderRegister(); const user = userEvent.setup();
    const password = screen.getByLabelText(locale === 'zh-CN' ? '密码' : 'Password');
    await user.type(password, '<raw secret>'); await user.tab();
    expect(screen.getByRole('button', { name: locale === 'zh-CN' ? '显示密码' : 'Show password' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(password).toHaveAttribute('type', 'text');
    expect(password).toHaveFocus();
    await act(async () => { await i18n.changeLanguage(locale === 'zh-CN' ? 'en-US' : 'zh-CN'); });
    expect(password).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: locale === 'zh-CN' ? 'Hide password' : '隐藏密码' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(password).toHaveAttribute('type', 'password');
    expect(password).toHaveValue('<raw secret>');
    expect(screen.queryByRole('img', { name: /eye/ })).not.toBeInTheDocument();
  });
});
