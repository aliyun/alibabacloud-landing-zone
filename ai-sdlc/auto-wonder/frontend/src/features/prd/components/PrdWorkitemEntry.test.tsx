import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { PrdWorkitemEntry } from './PrdWorkitemEntry';

describe('PrdWorkitemEntry', () => {
  it('retains clarification while hiding PRD design in Community workitems', async () => {
    const onClarify = vi.fn();
    render(<MemoryRouter><PrdWorkitemEntry onClarify={onClarify} /></MemoryRouter>);
    expect(screen.getByRole('navigation', { name: '需求与设计' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /PRD 设计/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /AI 需求澄清/ }));
    expect(onClarify).toHaveBeenCalledOnce();
    expect(screen.queryByText('交付模式')).not.toBeInTheDocument();
  });
  it('renders no tools when clarification is disabled', () => {
    render(<MemoryRouter><PrdWorkitemEntry /></MemoryRouter>);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
});
