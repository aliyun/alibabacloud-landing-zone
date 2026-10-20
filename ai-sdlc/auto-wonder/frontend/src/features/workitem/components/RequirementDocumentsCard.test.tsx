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
    expect(screen.getByText(/支持任意类型文件（包括 Excel）/)).toBeInTheDocument();
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

  it('accepts vector.svg through change', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const file = new File(['payload'], 'vector.svg', { type: 'image/svg+xml' });
    fireEvent.change(screen.getByTestId('requirement-document-file-input'), { target: { files: [file] } });
    expect(uploadMutate).toHaveBeenCalledWith({ files: [file] });
  });

  it('allows every file type in the picker and explains the limits', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    expect(screen.getByText(/支持任意类型文件/)).toBeInTheDocument();
    expect(screen.getByTestId('requirement-document-file-input')).not.toHaveAttribute('accept');
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

  it('accepts notes.xlsx through change', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const file = new File(['payload'], 'notes.xlsx', { type: 'application/vnd.ms-excel' });
    fireEvent.change(screen.getByTestId('requirement-document-file-input'), { target: { files: [file] } });
    expect(uploadMutate).toHaveBeenCalledWith({ files: [file] });
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

  it('accepts report.custom through drop', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const file = new File(['payload'], 'report.custom', { type: 'application/octet-stream' });
    fireEvent.drop(screen.getByTestId('requirement-document-dropzone'), { dataTransfer: droppedFiles([file]) });
    expect(uploadMutate).toHaveBeenCalledWith({ files: [file] });
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

  it('accepts legacy.xls through paste', () => {
    render(<RequirementDocumentsCard workitemId={1} documents={[]} />);
    const file = new File(['payload'], 'legacy.xls', { type: 'application/vnd.ms-excel' });
    fireEvent.paste(screen.getByTestId('requirement-document-dropzone'), { clipboardData: pastedImages(file) });
    expect(uploadMutate).toHaveBeenCalledWith({ files: [file] });
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
