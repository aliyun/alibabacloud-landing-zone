import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryDocumentEditor, memoryDocumentMeasurements } from './MemoryDocumentEditor';

describe('MemoryDocumentEditor', () => {
  it('measures Claude-compatible index boundaries in UTF-8 bytes and lines', () => {
    expect(memoryDocumentMeasurements('一\n二')).toEqual({ lines: 2, bytes: 7 });
    expect(memoryDocumentMeasurements('')).toEqual({ lines: 0, bytes: 0 });
  });

  it('warns but still allows an over-limit MEMORY.md to be saved', async () => {
    render(<MemoryDocumentEditor open writable saving={false} onCancel={vi.fn()} onSave={vi.fn()}
      document={{ id: 1, storeId: 1, path: 'MEMORY.md', contentMd: Array(201).fill('- [topic](topic.md) — hook').join('\n'), contentSha256: 'sha', byteSize: 0, modifiedAt: '2026-09-18T00:00:00Z', version: 2 }} />);

    expect(await screen.findByText(/仍可保存/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /保\s*存/ })).toBeEnabled();
  });
});
