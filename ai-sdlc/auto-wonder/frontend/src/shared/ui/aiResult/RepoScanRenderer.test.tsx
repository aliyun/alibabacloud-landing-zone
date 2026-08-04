import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RepoScanRenderer } from './RepoScanRenderer';
import type { RepoScanResult } from '@/shared/types/ai';

const base: RepoScanResult = {
  purpose: '订单服务',
  keyBusiness: ['下单'],
  upstreams: [],
  downstreams: [],
  summaryMd: '# 结论',
};

describe('RepoScanRenderer', () => {
  it('renders purpose and summary values', () => {
    render(<RepoScanRenderer value={base} onChange={() => {}} />);
    expect(screen.getByDisplayValue('订单服务')).toBeInTheDocument();
    expect(screen.getByDisplayValue('# 结论')).toBeInTheDocument();
    expect(screen.getByText('下单')).toBeInTheDocument();
  });

  it('emits updated purpose on edit', async () => {
    const onChange = vi.fn();
    render(<RepoScanRenderer value={base} onChange={onChange} />);
    const input = screen.getByDisplayValue('订单服务');
    await userEvent.type(input, 'X');
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ purpose: '订单服务X' }),
    );
  });

  it('adds a keyBusiness tag', async () => {
    const onChange = vi.fn();
    render(<RepoScanRenderer value={base} onChange={onChange} />);
    await userEvent.click(screen.getByText('+ 关键业务'));
    const tagInput = screen.getByRole('textbox', { name: /新增关键业务/ });
    await userEvent.type(tagInput, '支付{enter}');
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyBusiness: ['下单', '支付'] }),
    );
  });

  it('disables inputs when disabled', () => {
    render(<RepoScanRenderer value={base} onChange={() => {}} disabled />);
    expect(screen.getByDisplayValue('订单服务')).toBeDisabled();
  });
});
