import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';

const { uploadMutate } = vi.hoisted(() => ({ uploadMutate: vi.fn() }));

vi.mock('../hooks', () => ({
  useUploadRequirementDocuments: () => ({ mutate: uploadMutate, isPending: false }),
  useDeleteRequirementDocument: () => ({ mutate: vi.fn(), isPending: false }),
}));

vi.mock('../api', () => ({
  getArtifactDownloadUrl: vi.fn(),
}));

vi.mock('@/shared/auth/useAccessCommand', () => ({
  useAccessCommand: () => vi.fn((_required: unknown, _action: string, command: () => unknown) => command()),
}));

import { RequirementDocumentsCard } from './RequirementDocumentsCard';

describe('RequirementDocumentsCard', () => {
  beforeEach(() => {
    uploadMutate.mockClear();
  });

  it('shows requirement/design context terminology and limit guidance', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);

    expect(screen.getByText('需求/设计上下文')).toBeInTheDocument();
    expect(screen.getByText(/PNG、JPEG、WebP/)).toBeInTheDocument();
    expect(screen.getByText(/最多 10 个.*20 MB/)).toBeInTheDocument();
  });

  it('uploads a selected PNG file', async () => {
    const user = userEvent.setup();
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const input = screen.getByTestId('requirement-document-file-input') as HTMLInputElement;
    const file = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'screen.png', { type: 'image/png' });

    await user.upload(input, file);

    expect(uploadMutate).toHaveBeenCalledWith({ files: [file] });
  });

  it('rejects an unsupported SVG selection without uploading', () => {
    const errorSpy = vi.spyOn(message, 'error');
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const input = screen.getByTestId('requirement-document-file-input') as HTMLInputElement;
    const file = new File(['<svg/>'], 'unsafe.svg', { type: 'image/svg+xml' });

    // userEvent.upload filters against the input accept attribute; use fireEvent
    // to simulate a file picked through the OS dialog regardless of accept.
    fireEvent.change(input, { target: { files: [file] } });

    expect(errorSpy).toHaveBeenCalled();
    expect(uploadMutate).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });
});
