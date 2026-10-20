import { describe, it, expect } from 'vitest';
import { OTHER_SENTINEL, computeElicitationContent, validateElicitation } from './elicitationAnswer';
import type { ElicitationField } from './schemaForm';

const f = (over: Partial<ElicitationField>): ElicitationField =>
  ({ name: 'q0', label: '题', control: 'text', required: false, ...over });

describe('computeElicitationContent', () => {
  it('single-select: 其他想法文本替换原值', () => {
    const fields = [f({ control: 'select', required: true, options: [{ value: 'A', label: 'A' }] })];
    expect(computeElicitationContent(fields, { q0: OTHER_SENTINEL }, { q0: '自定义方案' }))
      .toEqual({ q0: '自定义方案' });
  });

  it('single-select: 选真实项回传该项', () => {
    const fields = [f({ control: 'select', options: [{ value: 'A', label: 'A' }] })];
    expect(computeElicitationContent(fields, { q0: 'A' }, {})).toEqual({ q0: 'A' });
  });

  it('multiselect: 自定义值原样追加到数组末尾，不添加会改变含义的前缀', () => {
    const fields = [f({ control: 'multiselect', options: [{ value: 'A', label: 'A' }] })];
    expect(computeElicitationContent(fields, { q0: ['A', OTHER_SENTINEL] }, { q0: 'cn-beijing' }))
      .toEqual({ q0: ['A', 'cn-beijing'] });
  });

  it('multiselect: 未勾其他想法则只回传已选', () => {
    const fields = [f({ control: 'multiselect', options: [{ value: 'A', label: 'A' }] })];
    expect(computeElicitationContent(fields, { q0: ['A'] }, {})).toEqual({ q0: ['A'] });
  });

  it('text: 原值逐字回传', () => {
    const fields = [f({ control: 'text' })];
    expect(computeElicitationContent(fields, { q0: '  原样  ' }, {})).toEqual({ q0: '  原样  ' });
  });

  it('空值/未答的可选字段不回传，绝不产生未声明字段', () => {
    const fields = [f({ name: 'a', control: 'text' }), f({ name: 'b', control: 'boolean' })];
    expect(computeElicitationContent(fields, { b: false }, {})).toEqual({ b: false });
  });

  it('json 控件解析对象后平铺，非法则跳过', () => {
    const fields = [f({ name: 'j', control: 'json' })];
    expect(computeElicitationContent(fields, { j: '{"x":1}' }, {})).toEqual({ x: 1 });
    expect(computeElicitationContent(fields, { j: 'not-json' }, {})).toEqual({});
  });
});

describe('validateElicitation', () => {
  it('必填空值给出「请填写「字段名」」并标记 firstInvalidIndex', () => {
    const fields = [f({ name: 'a', control: 'select', required: true }), f({ name: 'b', control: 'text', required: true })];
    const r = validateElicitation(fields, { a: 'A' }, {});
    expect(r.errors.b).toBe('请填写「题」');
    expect(r.firstInvalidIndex).toBe(1);
  });

  it('选了其他想法但输入为空：必填报「请填写其他想法」', () => {
    const fields = [f({ control: 'select', required: true })];
    const r = validateElicitation(fields, { q0: OTHER_SENTINEL }, { q0: '   ' });
    expect(r.errors.q0).toBe('请填写其他想法');
    expect(r.firstInvalidIndex).toBe(0);
  });

  it('多选勾其他想法但输入为空：必填报「请填写其他想法」', () => {
    const fields = [f({ control: 'multiselect', required: true })];
    const r = validateElicitation(fields, { q0: [OTHER_SENTINEL] }, { q0: '  ' });
    expect(r.errors.q0).toBe('请填写其他想法');
    expect(r.firstInvalidIndex).toBe(0);
  });

  it('全答完无错误，firstInvalidIndex 为 -1', () => {
    const fields = [f({ control: 'select', required: true })];
    const r = validateElicitation(fields, { q0: 'A' }, {});
    expect(r.errors).toEqual({});
    expect(r.firstInvalidIndex).toBe(-1);
  });
});
