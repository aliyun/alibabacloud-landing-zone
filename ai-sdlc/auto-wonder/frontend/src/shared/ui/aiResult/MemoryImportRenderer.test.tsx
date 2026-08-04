import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryImportRenderer } from './MemoryImportRenderer';
import type { MemoryImportResult } from '@/shared/types/ai';

const base: MemoryImportResult = {
  items: [
    { type: '项目知识', title: '构建方式', contentMd: '用 maven' },
    { type: '避坑', title: '别改配置', contentMd: '会炸' },
  ],
};

describe('MemoryImportRenderer', () => {
  it('renders each item title', () => {
    render(<MemoryImportRenderer value={base} onChange={() => {}} />);
    expect(screen.getByDisplayValue('构建方式')).toBeInTheDocument();
    expect(screen.getByDisplayValue('别改配置')).toBeInTheDocument();
  });

  it('discarding an item removes it from onChange output', async () => {
    const onChange = vi.fn();
    render(<MemoryImportRenderer value={base} onChange={onChange} />);
    const discardButtons = screen.getAllByRole('button', { name: /丢弃/ });
    await userEvent.click(discardButtons[0]);
    expect(onChange).toHaveBeenLastCalledWith({
      items: [{ type: '避坑', title: '别改配置', contentMd: '会炸' }],
    });
  });

  it('edits a title', async () => {
    const onChange = vi.fn();
    render(<MemoryImportRenderer value={base} onChange={onChange} />);
    const input = screen.getByDisplayValue('构建方式');
    await userEvent.type(input, '!');
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({
        items: expect.arrayContaining([
          expect.objectContaining({ title: '构建方式!' }),
        ]),
      }),
    );
  });
});
