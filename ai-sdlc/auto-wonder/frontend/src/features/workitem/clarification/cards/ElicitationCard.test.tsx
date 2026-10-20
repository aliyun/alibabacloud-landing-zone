import { describe, it, expect, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { i18n } from '@/shared/i18n';
import { ElicitationCard } from './ElicitationCard';

const selectSchema = {
  type: 'object',
  title: '技术选型',
  properties: {
    q0: {
      type: 'string',
      title: '首选技术栈',
      oneOf: [{ const: 'nextjs', title: 'Next.js 全栈' }, { const: 'vite', title: 'Vite + Spring' }],
    },
  },
  required: ['q0'],
};

// 实测 qodercli：问题正文塞在 description 首行，title 只是短话题标签，
// 后续 `<选项>: <说明>` 行是选项副标题。实时向导同时渲染 label 与 description，
// 已答卡片若只回显 label，用户在执行详情里就只看到话题与答案、看不到真正的问题。
const describedSchema = {
  type: 'object',
  title: 'Answer Questions',
  properties: {
    q0: {
      type: 'string',
      title: '技术栈',
      description: '项目采用什么技术栈?(仓库目前是空的,需要先定基础)\nNext.js 全栈: 推荐\nVite + Spring: 前后端分离',
      oneOf: [{ const: 'Next.js 全栈', title: 'Next.js 全栈' }, { const: 'Vite + Spring', title: 'Vite + Spring' }],
    },
  },
  required: ['q0'],
};

// 进行中的问题已改由 ElicitationWizard 浮层承担，ElicitationCard 只负责 resolved 内联回显。
describe('ElicitationCard（resolved 内联展示）', () => {
  afterEach(async () => { await act(async () => { await i18n.changeLanguage('zh-CN'); }); });
  it.each([undefined, 'unknown', 'CONFIRMATION_REQUIRED'])('keeps restored questions and answers raw while translating an explicit heading (%s)', async (kind) => {
    const restoredSchema = { ...describedSchema, 'x-autowonder-message-kind': kind };
    render(<ElicitationCard requestId="r1" message="需要你的确认" schema={restoredSchema} resolved action="accept" answers={{ q0: '原始答案 {0}' }} />);
    await act(async () => { await i18n.changeLanguage('en-US'); });
    expect(screen.getByText(kind === 'CONFIRMATION_REQUIRED' ? 'Your confirmation is needed' : '需要你的确认')).toBeInTheDocument();
    expect(screen.getByText('项目采用什么技术栈?(仓库目前是空的,需要先定基础)')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-answer-q0')).toHaveTextContent('原始答案 {0}');
    expect(restoredSchema.properties.q0.description).toBe(describedSchema.properties.q0.description);
  });
  it('回显已回答的最终值并标记「已回答」', () => {
    render(
      <ElicitationCard requestId="r1" schema={selectSchema} resolved action="accept" answers={{ q0: 'nextjs' }} />,
    );
    expect(screen.getByTestId('elicitation-answer-q0')).toHaveTextContent('nextjs');
    expect(screen.getByText('已回答')).toBeInTheDocument();
    expect(screen.queryByTestId('elicitation-submit-r1')).toBeNull();
    expect(screen.queryByTestId('elicitation-skip-r1')).toBeNull();
  });

  // 执行详情里的已答卡片必须能看到「问题」本身，而不只是答案。
  // qodercli 把问题正文放在 description 首行，label 只是短话题，缺了 description
  // 用户就只看到「技术栈：Next.js 全栈」，读不出当初到底问了什么。
  it('已答卡片回显问题正文（description），而不只是话题标签与答案', () => {
    render(
      <ElicitationCard requestId="r1" schema={describedSchema} resolved action="accept" answers={{ q0: 'Next.js 全栈' }} />,
    );
    expect(screen.getByText('项目采用什么技术栈?(仓库目前是空的,需要先定基础)')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-answer-q0')).toHaveTextContent('Next.js 全栈');
  });

  // 重连补拉只拿到 resolved 帧时 requestedSchema 缺失，降级表单只有 _raw 字段，
  // 答案键 q0 找不到对应字段 —— 卡片要回退展示原始键 + 答案，不能崩。
  it('schema 缺失时回退展示原始答案键，不抛错', () => {
    render(
      <ElicitationCard requestId="r1" schema={undefined} resolved action="accept" answers={{ q0: 'A' }} />,
    );
    expect(screen.getByText('q0')).toBeInTheDocument();
    expect(screen.getByTestId('elicitation-answer-q0')).toHaveTextContent('A');
  });

  it('跳过标记「已跳过」且不回显答案', () => {
    render(<ElicitationCard requestId="r1" schema={selectSchema} resolved action="decline" />);
    expect(screen.getByText('已跳过')).toBeInTheDocument();
    expect(screen.queryByTestId('elicitation-answer-q0')).toBeNull();
  });

  it('取消标记「已取消」', () => {
    render(<ElicitationCard requestId="r1" schema={selectSchema} resolved action="cancel" />);
    expect(screen.getByText('已取消')).toBeInTheDocument();
  });

  it('未知 action 标记「已处理」', () => {
    render(<ElicitationCard requestId="r1" schema={selectSchema} resolved />);
    expect(screen.getByText('已处理')).toBeInTheDocument();
  });

  it('对象答案以 JSON 回显', () => {
    render(
      <ElicitationCard requestId="r4" schema={{ anyOf: [] }} resolved action="accept" answers={{ _raw: { nested: true } }} />,
    );
    expect(screen.getByTestId('elicitation-answer-_raw').textContent).toContain('nested');
  });

  it('未 resolved 时只渲染头部，不渲染任何交互控件', () => {
    render(<ElicitationCard requestId="r1" message="需要你的确认" schema={selectSchema} />);
    expect(screen.getByTestId('elicitation-card-r1')).toBeInTheDocument();
    expect(screen.getByText('需要你的确认')).toBeInTheDocument();
    expect(screen.queryByTestId('elicitation-submit-r1')).toBeNull();
    expect(screen.queryByTestId('elicitation-skip-r1')).toBeNull();
    expect(screen.queryByTestId('elicitation-option-q0-0')).toBeNull();
  });
});
