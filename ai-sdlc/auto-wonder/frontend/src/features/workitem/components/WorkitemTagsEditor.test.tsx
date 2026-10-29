import { describe, expect, it, vi, afterEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { message } from 'antd';
import {
  MAX_WORKITEM_TAGS,
  MAX_WORKITEM_TAG_LENGTH,
  normalizeTagDraft,
  WorkitemTagsEditor,
} from './WorkitemTagsEditor';

function renderEditor(props: Partial<React.ComponentProps<typeof WorkitemTagsEditor>> = {}) {
  return render(<WorkitemTagsEditor initialTags={[]} onSave={() => undefined} {...props} />);
}

const selectedTexts = () => Array.from(document.querySelectorAll('.ant-select-selection-item')).map((item) => item.textContent);

describe('normalizeTagDraft', () => {
  it('trims, drops blanks and de-duplicates while keeping the first occurrence order', () => {
    expect(normalizeTagDraft([' 发布 ', '发布', '', '  ', '线上'])).toEqual({ tags: ['发布', '线上'], error: null });
    expect(normalizeTagDraft(['b', 'a', 'b'])).toEqual({ tags: ['b', 'a'], error: null });
  });

  it('accepts the exact count and length boundaries', () => {
    const tags = Array.from({ length: MAX_WORKITEM_TAGS }, (_, index) => `${'x'.repeat(MAX_WORKITEM_TAG_LENGTH - 2)}${String(index).padStart(2, '0')}`);
    expect(normalizeTagDraft(tags)).toEqual({ tags, error: null });
  });

  it('reports an over-long tag even when the count is also exceeded', () => {
    const tags = Array.from({ length: MAX_WORKITEM_TAGS + 1 }, (_, index) => `t${index}`);
    tags[0] = 'x'.repeat(MAX_WORKITEM_TAG_LENGTH + 1);
    expect(normalizeTagDraft(tags).error).toBe('tooLong');
  });

  it('rejects more than the allowed number of distinct tags', () => {
    const tags = Array.from({ length: MAX_WORKITEM_TAGS + 1 }, (_, index) => `t${index}`);
    expect(normalizeTagDraft(tags)).toMatchObject({ error: 'tooMany' });
  });
});

describe('WorkitemTagsEditor', () => {
  afterEach(() => { message.destroy(); });

  it('shows the normalized count and saves trimmed unique tags', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);
    renderEditor({ initialTags: [' 发布 ', '发布', '  '], onSave });

    expect(screen.getByText('1 / 50')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '保存' }));
    expect(onSave).toHaveBeenCalledWith(['发布']);
  });

  it('adds a typed tag before saving', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);
    renderEditor({ initialTags: ['发布'], onSave });

    await user.type(screen.getByRole('combobox'), '线上');
    fireEvent.keyDown(screen.getByRole('combobox'), { key: 'Enter', keyCode: 13, which: 13 });
    await waitFor(() => expect(selectedTexts()).toContain('线上'));
    await user.click(screen.getByRole('button', { name: '保存' }));
    expect(onSave).toHaveBeenCalledWith(['发布', '线上']);
  });

  it('offers the workspace tags plus current tags as suggestions without duplicates', async () => {
    const user = userEvent.setup();
    renderEditor({ initialTags: ['发布'], options: ['发布', '线上'] });

    await user.click(screen.getByRole('combobox'));
    expect(await screen.findByText('线上', { selector: '.ant-select-item-option-content' })).toBeInTheDocument();
    expect(screen.getAllByText('发布', { selector: '.ant-select-item-option-content' })).toHaveLength(1);
  });

  it('disables saving and explains an over-long tag', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();
    renderEditor({ initialTags: ['x'.repeat(MAX_WORKITEM_TAG_LENGTH + 1)], onSave });

    expect(screen.getByText('单个标签不能超过 64 个字符')).toBeInTheDocument();
    const save = screen.getByRole('button', { name: '保存' });
    expect(save).toBeDisabled();
    await user.click(save);
    expect(onSave).not.toHaveBeenCalled();
  });

  it('disables saving and explains too many tags', () => {
    renderEditor({ initialTags: Array.from({ length: MAX_WORKITEM_TAGS + 1 }, (_, index) => `t${index}`) });
    expect(screen.getByText('最多只能添加 50 个标签')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled();
  });

  it('keeps the draft and reports the failure when saving is rejected', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockRejectedValue(new Error('标签服务异常'));
    renderEditor({ initialTags: ['发布'], onSave });

    await user.click(screen.getByRole('button', { name: '保存' }));

    expect(await screen.findByText('标签服务异常')).toBeInTheDocument();
    expect(selectedTexts()).toContain('发布');
    expect(screen.getByRole('button', { name: '保存' })).toBeEnabled();
  });

  it('ignores a second save while the first request is in flight', async () => {
    const user = userEvent.setup();
    let release: (() => void) | undefined;
    const onSave = vi.fn(() => new Promise<void>((resolve) => { release = resolve; }));
    renderEditor({ initialTags: ['发布'], onSave });

    const save = screen.getByRole('button', { name: '保存' });
    await user.click(save);
    await user.click(save);

    expect(onSave).toHaveBeenCalledTimes(1);
    await act(async () => { release?.(); });
  });

  it('shows the loading state while the parent is saving', () => {
    renderEditor({ initialTags: ['发布'], saving: true });
    expect(screen.getByRole('button', { name: '保存' })).toHaveClass('ant-btn-loading');
  });

  it('calls onCancel and omits the action when no cancel handler is given', async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    const view = renderEditor({ initialTags: [], onCancel });

    await user.click(screen.getByRole('button', { name: /取\s*消/ }));
    expect(onCancel).toHaveBeenCalledTimes(1);

    view.rerender(<WorkitemTagsEditor initialTags={[]} onSave={() => undefined} />);
    expect(screen.queryByRole('button', { name: /取\s*消/ })).not.toBeInTheDocument();
  });
});
