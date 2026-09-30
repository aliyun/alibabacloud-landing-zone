import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryTopicEditor } from './MemoryTopicEditor';
import type { MemoryOwner } from './api';

const squad: MemoryOwner = { scope: 'SQUAD', ownerRef: 12, name: '社区发布小队', squadNames: [], canCreate: true };

describe('MemoryTopicEditor', () => {
  it('submits semantic scope and content without requiring a file path', async () => {
    const save = vi.fn().mockResolvedValue(undefined);
    render(<MemoryTopicEditor open owners={[squad]} initialOwner={squad} saving={false} onCancel={() => {}} onSave={save} />);
    await userEvent.type(screen.getByLabelText('标题'), '发布约定');
    await userEvent.type(screen.getByLabelText('适用说明'), '同步社区时');
    await userEvent.type(screen.getByLabelText('正文'), '保留上游功能语义');
    await userEvent.click(screen.getByRole('button', { name: '保 存' }));
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    const [owner, topic] = save.mock.calls[0];
    expect(owner.scope).toBe('SQUAD'); expect(owner.ownerRef).toBe(12);
    expect(topic).toMatchObject({ type: 'reference', title: '发布约定', description: '同步社区时', contentMd: '保留上游功能语义' });
    expect(topic.path).toBeUndefined(); expect(topic.idempotencyKey).toBeTruthy();
  });

  it('directory refresh does not erase in-progress human edits', async () => {
    const props = { open: true, owners: [squad], initialOwner: squad, saving: false, onCancel: () => {}, onSave: vi.fn() };
    const { rerender } = render(<MemoryTopicEditor {...props} />);
    await userEvent.type(screen.getByLabelText('标题'), '尚未保存的标题');
    rerender(<MemoryTopicEditor {...props} owners={[{ ...squad }]} initialOwner={{ ...squad }} />);
    expect(screen.getByLabelText('标题')).toHaveValue('尚未保存的标题');
  });
});
