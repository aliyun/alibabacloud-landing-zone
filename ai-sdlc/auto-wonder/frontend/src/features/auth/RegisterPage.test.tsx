import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
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
  it('renders registration form inside the product command center landing', () => {
    renderRegister();
    expect(screen.getAllByRole('button', { name: '切换外观' })).toHaveLength(1);
    expect(screen.getByText('AI Native SDLC Platform')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /软件产品\s*自动交付平台/ })).toBeInTheDocument();
    expect(screen.getByText('创建平台账号')).toBeInTheDocument();
    expect(screen.getByLabelText(/用户名/)).toBeInTheDocument();
    expect(screen.getByLabelText(/昵称/)).toBeInTheDocument();
    expect(screen.getByLabelText(/邮箱/)).toBeInTheDocument();
    expect(screen.getByLabelText(/密码/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /注\s*册/ })).toBeInTheDocument();
  });
});
