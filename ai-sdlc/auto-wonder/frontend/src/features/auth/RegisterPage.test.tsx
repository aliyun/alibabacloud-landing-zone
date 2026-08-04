import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { RegisterPage } from './RegisterPage';

function renderRegister() {
  return render(
    <MemoryRouter>
      <RegisterPage />
    </MemoryRouter>,
  );
}

describe('RegisterPage', () => {
  it('renders registration form inside the product command center landing', () => {
    renderRegister();
    expect(screen.getByText('AutoWonder · Agent SDLC')).toBeInTheDocument();
    expect(screen.getByText('登录后，把工单交给数字员工小队')).toBeInTheDocument();
    expect(screen.getByText('创建 AutoWonder 账号')).toBeInTheDocument();
    expect(screen.getByLabelText(/用户名/)).toBeInTheDocument();
    expect(screen.getByLabelText(/昵称/)).toBeInTheDocument();
    expect(screen.getByLabelText(/邮箱/)).toBeInTheDocument();
    expect(screen.getByLabelText(/密码/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /注\s*册/ })).toBeInTheDocument();
  });
});
