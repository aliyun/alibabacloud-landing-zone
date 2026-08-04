import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { WorkitemHealthBadge } from './WorkitemHealthBadge';

describe('WorkitemHealthBadge', () => {
  it('renders warning tag when health is STUCK', () => {
    render(<WorkitemHealthBadge item={{ health: 'STUCK', healthReason: '执行超时' }} />);
    expect(screen.getByText('异常')).toBeInTheDocument();
  });

  it('renders nothing when health is OK', () => {
    const { container } = render(<WorkitemHealthBadge item={{ health: 'OK', healthReason: null }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when health is undefined', () => {
    const { container } = render(<WorkitemHealthBadge item={{}} />);
    expect(container).toBeEmptyDOMElement();
  });
});
