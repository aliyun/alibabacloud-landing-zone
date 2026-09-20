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
import type { Artifact } from '@/shared/types/workitem';

const UNSUPPORTED_MESSAGE = '仅支持上传 .md、.markdown、.txt、.html、.pdf、.png、.jpg、.jpeg、.webp、.docx、.doc、.java、.py、.zip 文件';

function droppedFiles(files: File[]) {
  return {
    types: ['Files'],
    items: files.map((entry) => ({ kind: 'file', getAsFile: () => entry })),
    files,
  };
}

function pastedImages(...files: File[]) {
  return { items: files.map((entry) => ({ kind: 'file', getAsFile: () => entry })) };
}

function exportArtifact(name: string): Artifact {
  return { id: 7, workitemId: 1, dispatchId: null, name, type: 'REQUIREMENT_DOC', size: 4, gmtCreate: '' };
}

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

  it('advertises word, source code and archive formats in the hint and the picker', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);

    expect(screen.getByText(/Word（\.docx\/\.doc）、Java、Python、ZIP/)).toBeInTheDocument();
    const input = screen.getByTestId('requirement-document-file-input') as HTMLInputElement;
    for (const extension of ['.docx', '.doc', '.java', '.py', '.zip']) {
      expect(input.accept).toContain(extension);
    }
  });

  it.each([
    ['spec.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
    ['legacy.doc', 'application/msword'],
    ['Sample.java', 'text/x-java-source'],
    ['helper.py', 'text/x-python'],
    ['assets.zip', 'application/zip'],
  ])('uploads a selected %s file', async (name, type) => {
    const user = userEvent.setup();
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const input = screen.getByTestId('requirement-document-file-input') as HTMLInputElement;
    const file = new File(['payload'], name, { type });

    // userEvent.upload honours the accept attribute, so reaching the mutation
    // proves the picker itself offers the format.
    await user.upload(input, file);

    expect(uploadMutate).toHaveBeenCalledWith({ files: [file] });
  });

  it('rejects an unsupported extension and reports the full whitelist', () => {
    const errorSpy = vi.spyOn(message, 'error');
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const input = screen.getByTestId('requirement-document-file-input') as HTMLInputElement;

    fireEvent.change(input, {
      target: { files: [new File(['sheet'], 'notes.xlsx', { type: 'application/vnd.ms-excel' })] },
    });

    expect(errorSpy).toHaveBeenCalledWith(UNSUPPORTED_MESSAGE);
    expect(uploadMutate).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it('highlights the upload area while a file drag hovers and uploads dropped files', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const zone = screen.getByTestId('requirement-document-dropzone');
    const file = new File(['# Plan'], 'plan.md', { type: 'text/markdown' });

    fireEvent.dragEnter(zone, { dataTransfer: { types: ['Files'] } });
    expect(zone).toHaveAttribute('data-drag-active', 'true');

    fireEvent.drop(zone, { dataTransfer: droppedFiles([file]) });

    expect(zone).toHaveAttribute('data-drag-active', 'false');
    expect(uploadMutate).toHaveBeenCalledWith({ files: [file] });
  });

  it('clears the drag highlight when the pointer leaves without dropping', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const zone = screen.getByTestId('requirement-document-dropzone');

    fireEvent.dragEnter(zone, { dataTransfer: { types: ['Files'] } });
    fireEvent.dragLeave(zone, { dataTransfer: { types: ['Files'] } });

    expect(zone).toHaveAttribute('data-drag-active', 'false');
  });

  it('reports dropped folders and non-file content instead of failing silently', () => {
    const errorSpy = vi.spyOn(message, 'error');
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const zone = screen.getByTestId('requirement-document-dropzone');

    fireEvent.drop(zone, {
      dataTransfer: { types: ['Files'], items: [{ kind: 'file', webkitGetAsEntry: () => ({ isDirectory: true }) }] },
    });
    expect(errorSpy).toHaveBeenCalledWith('不支持上传文件夹，请拖入文件');

    fireEvent.drop(zone, {
      dataTransfer: { types: ['text/uri-list'], items: [{ kind: 'string' }], files: [] },
    });
    expect(errorSpy).toHaveBeenCalledWith('未识别到可上传的文件，请拖入本地文件');

    expect(uploadMutate).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it('applies the picker validation copy to dropped files', () => {
    const errorSpy = vi.spyOn(message, 'error');
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const zone = screen.getByTestId('requirement-document-dropzone');

    fireEvent.drop(zone, {
      dataTransfer: droppedFiles([new File(['sheet'], 'notes.xlsx', { type: 'application/vnd.ms-excel' })]),
    });

    expect(errorSpy).toHaveBeenCalledWith(UNSUPPORTED_MESSAGE);
    expect(uploadMutate).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it('rejects a dropped file whose name already exists', () => {
    const errorSpy = vi.spyOn(message, 'error');
    render(<RequirementDocumentsCard workitemId={1} documents={[exportArtifact('requirements/plan.md')]} />);
    const zone = screen.getByTestId('requirement-document-dropzone');

    fireEvent.drop(zone, {
      dataTransfer: droppedFiles([new File(['# Plan'], 'plan.md', { type: 'text/markdown' })]),
    });

    expect(errorSpy).toHaveBeenCalledWith('附件已存在：plan.md');
    expect(uploadMutate).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it('uploads a pasted screenshot under a generated unique name', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const zone = screen.getByTestId('requirement-document-dropzone');
    const shot = new File(['png-bytes'], 'image.png', { type: 'image/png' });

    const notPrevented = fireEvent.paste(zone, { clipboardData: pastedImages(shot) });

    expect(notPrevented).toBe(false);
    expect(uploadMutate).toHaveBeenCalledTimes(1);
    const uploaded = (uploadMutate.mock.calls[0][0] as { files: File[] }).files;
    expect(uploaded).toHaveLength(1);
    expect(uploaded[0].name).toMatch(/^粘贴-\d{8}-\d{6}-\d+\.png$/);
    expect(uploaded[0].type).toBe('image/png');
  });

  it('gives every screenshot in one paste a unique name so repeats never conflict', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const zone = screen.getByTestId('requirement-document-dropzone');

    fireEvent.paste(zone, {
      clipboardData: pastedImages(
        new File(['first'], 'image.png', { type: 'image/png' }),
        new File(['second'], 'image.png', { type: 'image/png' }),
      ),
    });

    const uploaded = (uploadMutate.mock.calls[0][0] as { files: File[] }).files;
    expect(uploaded).toHaveLength(2);
    expect(uploaded[0].name).not.toBe(uploaded[1].name);
    for (const entry of uploaded) expect(entry.name).toMatch(/^粘贴-.*\.png$/);
  });

  it('keeps consecutive pasted screenshots distinct across separate pastes', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const zone = screen.getByTestId('requirement-document-dropzone');

    fireEvent.paste(zone, { clipboardData: pastedImages(new File(['one'], 'image.png', { type: 'image/png' })) });
    fireEvent.paste(zone, { clipboardData: pastedImages(new File(['two'], 'image.png', { type: 'image/png' })) });

    expect(uploadMutate).toHaveBeenCalledTimes(2);
    const firstName = (uploadMutate.mock.calls[0][0] as { files: File[] }).files[0].name;
    const secondName = (uploadMutate.mock.calls[1][0] as { files: File[] }).files[0].name;
    expect(firstName).not.toBe(secondName);
  });

  it('rejects an unsupported pasted file with the shared copy', () => {
    const errorSpy = vi.spyOn(message, 'error');
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const zone = screen.getByTestId('requirement-document-dropzone');

    fireEvent.paste(zone, {
      clipboardData: pastedImages(new File(['gif'], 'anim.gif', { type: 'image/gif' })),
    });

    expect(errorSpy).toHaveBeenCalledWith(UNSUPPORTED_MESSAGE);
    expect(uploadMutate).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it('leaves plain-text paste untouched', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const zone = screen.getByTestId('requirement-document-dropzone');

    const notPrevented = fireEvent.paste(zone, {
      clipboardData: { items: [{ kind: 'string', type: 'text/plain' }], files: [] },
    });

    expect(notPrevented).toBe(true);
    expect(uploadMutate).not.toHaveBeenCalled();
  });

  it('blocks the browser default for file drops outside the upload area', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);

    expect(fireEvent.drop(document.body, {
      dataTransfer: { types: ['Files'], files: [new File(['# Plan'], 'plan.md', { type: 'text/markdown' })] },
    })).toBe(false);
    expect(uploadMutate).not.toHaveBeenCalled();
  });
});
