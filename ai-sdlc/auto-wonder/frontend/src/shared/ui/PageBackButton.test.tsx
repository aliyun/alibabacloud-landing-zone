import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { PageBackButton } from './PageBackButton';

describe('PageBackButton', () => {
  it.each([true, false])('navigates to its explicit parent with compact=%s', async (compact) => {
    render(<MemoryRouter initialEntries={['/detail']}><Routes>
      <Route path="/detail" element={<PageBackButton to="/list" label="返回列表" compact={compact} />} />
      <Route path="/list" element={<h1>列表页</h1>} />
    </Routes></MemoryRouter>);
    const button = screen.getByRole('button', { name: '返回列表' });
    expect(button).toHaveClass('ant-btn-default');
    if (compact) expect(button).not.toHaveTextContent('返回列表');
    else expect(button).toHaveTextContent('返回列表');
    await userEvent.click(button);
    expect(await screen.findByRole('heading', { name: '列表页' })).toBeInTheDocument();
  });
});
