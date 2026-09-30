import { renderToStaticMarkup } from 'react-dom/server';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ElicitationField } from './ElicitationField';
import { OTHER_SENTINEL } from './elicitationAnswer';

import type { ElicitationField as F } from './schemaForm';

const selectField: F = {
  name: 'q0', label: '技术栈', control: 'select', required: true,
  options: [{ value: 'A', label: 'A' }, { value: 'B', label: 'B' }],
};
const multiField: F = {
  name: 'q1', label: '交付物', control: 'multiselect', required: true,
  options: [{ value: 'C', label: 'C' }, { value: 'D', label: 'D' }],
};
const textField: F = { name: 'q2', label: '补充', control: 'text', required: false };

const noop = () => {};

describe('ElicitationField', () => {
  it('单选渲染真实选项 + 末尾「有其他想法」', () => {
    render(<ElicitationField field={selectField} value={undefined} otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    expect(screen.getByTestId('elicitation-option-q0-0')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-option-q0-other')).toBeInTheDocument();
    expect(screen.getByText('有其他想法')).toBeInTheDocument();
  });

  it('选中「有其他想法」展开内联输入', () => {
    render(<ElicitationField field={selectField} value={OTHER_SENTINEL} otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    const input = screen.getByTestId('elicitation-other-input-q0');
    expect(input).toBeInTheDocument();
    expect(input).toHaveAttribute('placeholder', '输入其他想法');
  });

  it('未选「有其他想法」时不渲染内联输入', () => {
    render(<ElicitationField field={selectField} value="A" otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    expect(screen.queryByTestId('elicitation-other-input-q0')).toBeNull();
  });

  it('点真实单选项回调 onValueChange + onPickOption', () => {
    const onValueChange = vi.fn();
    const onPickOption = vi.fn();
    render(<ElicitationField field={selectField} value={undefined} otherText="" onValueChange={onValueChange} onOtherTextChange={noop} onPickOption={onPickOption} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-1'));
    expect(onValueChange).toHaveBeenCalledWith('B');
    expect(onPickOption).toHaveBeenCalledWith('B');
  });

  it('点「有其他想法」回调 onValueChange(OTHER) 且不触发 onPickOption', () => {
    const onValueChange = vi.fn();
    const onPickOption = vi.fn();
    render(<ElicitationField field={selectField} value={undefined} otherText="" onValueChange={onValueChange} onOtherTextChange={noop} onPickOption={onPickOption} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q0-other'));
    expect(onValueChange).toHaveBeenCalledWith(OTHER_SENTINEL);
    expect(onPickOption).not.toHaveBeenCalled();
  });

  it('多选勾选「有其他想法」并入数组', () => {
    const onValueChange = vi.fn();
    render(<ElicitationField field={multiField} value={['C']} otherText="" onValueChange={onValueChange} onOtherTextChange={noop} />);
    fireEvent.click(screen.getByTestId('elicitation-option-q1-other'));
    expect(onValueChange).toHaveBeenCalledWith(['C', OTHER_SENTINEL]);
  });

  it('多选勾选「有其他想法」后展开内联输入', () => {
    render(<ElicitationField field={multiField} value={['C', OTHER_SENTINEL]} otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    expect(screen.getByTestId('elicitation-other-input-q1')).toBeInTheDocument();
  });

  it('自由文本无「有其他想法」', () => {
    render(<ElicitationField field={textField} value="" otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    expect(screen.getByTestId('elicitation-field-q2')).toBeInTheDocument();
    expect(screen.queryByTestId('elicitation-option-q2-other')).toBeNull();
  });

  it('布尔渲染开关，切换回调 onValueChange', () => {
    const onValueChange = vi.fn();
    const boolField: F = { name: 'b', label: '开关', control: 'boolean', required: false };
    render(<ElicitationField field={boolField} value={false} otherText="" onValueChange={onValueChange} onOtherTextChange={noop} />);
    fireEvent.click(screen.getByTestId('elicitation-field-b'));
    expect(onValueChange).toHaveBeenCalledWith(true);
  });

  it('数字渲染数字输入框', () => {
    const numField: F = { name: 'n', label: '数量', control: 'number', required: false };
    render(<ElicitationField field={numField} value={5} otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    expect(screen.getByTestId('elicitation-field-n')).toBeInTheDocument();
  });

  it('JSON 降级渲染文本域与原始 schema 折叠', () => {
    const jsonField: F = { name: 'j', label: '扩展', control: 'json', required: false, rawSchema: { anyOf: [{ type: 'object' }] } };
    render(<ElicitationField field={jsonField} value="" otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    expect(screen.getByTestId('elicitation-field-j')).toBeInTheDocument();
    expect(screen.getByText('原始 Schema')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-raw-schema-j').textContent).toContain('anyOf');
  });

  it('error 非空渲染红字', () => {
    render(<ElicitationField field={selectField} value={undefined} otherText="" error="请填写「技术栈」" onValueChange={noop} onOtherTextChange={noop} />);
    expect(screen.getByTestId('elicitation-error-q0')).toHaveTextContent('请填写「技术栈」');
  });

  // 选中「有其他想法」后内联输入要自动聚焦（浏览器随之滚动到可见），否则用户得手动滑下去找输入框。
  it('单选：点选「有其他想法」后内联输入聚焦', () => {
    const { rerender } = render(<ElicitationField field={selectField} value={undefined} otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    rerender(<ElicitationField field={selectField} value={OTHER_SENTINEL} otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    expect(screen.getByTestId('elicitation-other-input-q0')).toHaveFocus();
  });

  it('多选：勾选「有其他想法」后内联输入聚焦', () => {
    const { rerender } = render(<ElicitationField field={multiField} value={[]} otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    rerender(<ElicitationField field={multiField} value={[OTHER_SENTINEL]} otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    expect(screen.getByTestId('elicitation-other-input-q1')).toHaveFocus();
  });

  // 回看（换题回到已选 OTHER 的题）是「挂载即已选」，不能抢走刚点的步骤点按钮焦点。
  it('挂载即已选「有其他想法」时不自动聚焦', () => {
    render(<ElicitationField field={selectField} value={OTHER_SENTINEL} otherText="已有内容" onValueChange={noop} onOtherTextChange={noop} />);
    expect(screen.getByTestId('elicitation-other-input-q0')).not.toHaveFocus();
  });

  // 单选/多选「有其他想法」都必须是 1px 浅色细虚线框，而非只写 borderStyle 导致的默认 3px 黑框。
  it('单选「有其他想法」是 1px 浅色细虚线框', () => {
    render(<ElicitationField field={selectField} value={undefined} otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    expect(screen.getByTestId('elicitation-option-q0-other').closest('label'))
      .toHaveStyle({ borderWidth: '1px', borderStyle: 'dashed' });
    const html = renderToStaticMarkup(<ElicitationField field={selectField} value={undefined} otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    expect(html).toContain('border-color:var(--aw-border)');
  });

  it('多选「有其他想法」是 1px 浅色细虚线框', () => {
    render(<ElicitationField field={multiField} value={[]} otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    expect(screen.getByTestId('elicitation-option-q1-other').closest('label'))
      .toHaveStyle({ borderWidth: '1px', borderStyle: 'dashed' });
    const html = renderToStaticMarkup(<ElicitationField field={selectField} value={undefined} otherText="" onValueChange={noop} onOtherTextChange={noop} />);
    expect(html).toContain('border-color:var(--aw-border)');
  });
});
