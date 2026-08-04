import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AboutAutoWonderPage } from './AboutAutoWonderPage';

describe('AboutAutoWonderPage', () => {
  it('explains the product narrative and quick-start workflow', () => {
    render(<AboutAutoWonderPage />);

    expect(screen.getByText('关于 AutoWonder')).toBeInTheDocument();
    expect(screen.getByText(/把工单交给一支/)).toBeInTheDocument();
    expect(screen.getByText(/会协作、会沉淀的数字员工小队/)).toBeInTheDocument();
    expect(screen.getByText('工单系统集成')).toBeInTheDocument();
    expect(screen.getByText('托管仓库')).toBeInTheDocument();
    expect(screen.getByText('设置角色与小队')).toBeInTheDocument();
    expect(screen.getByText('绑定 SDLC')).toBeInTheDocument();
    expect(screen.getByText('开始工单执行')).toBeInTheDocument();
    expect(screen.getByText('沉淀记忆与产物')).toBeInTheDocument();
    expect(screen.getByText('不是聊天机器人，而是研发编排系统')).toBeInTheDocument();
    expect(screen.getByText('数字员工需要小队协作')).toBeInTheDocument();
    expect(screen.getByText('每次交付都让组织更聪明')).toBeInTheDocument();
  });

  it('matches the approved visual mock content structure', () => {
    render(<AboutAutoWonderPage />);

    expect(screen.getByText('5 分钟理解工作流')).toBeInTheDocument();
    expect(screen.getByText('从工单到产物的自动交付闭环')).toBeInTheDocument();
    expect(screen.getByText('工单 #30020 · 支付回调稳定性修复')).toBeInTheDocument();
    expect(screen.getByText('来自 Aone / Jira / 自建工单系统')).toBeInTheDocument();
    expect(screen.getByText('PM')).toBeInTheDocument();
    expect(screen.getByText('DEV')).toBeInTheDocument();
    expect(screen.getByText('CR')).toBeInTheDocument();
    expect(screen.getByText('QA')).toBeInTheDocument();
    expect(screen.getByText('从现有研发体系接入 AutoWonder')).toBeInTheDocument();
    expect(screen.getByText('这条路径兼容 Aone、Jira、GitLab/GitHub、自建仓库与独立执行器，不要求推翻现有流程。')).toBeInTheDocument();
    expect(screen.getByText('理念 01')).toBeInTheDocument();
    expect(screen.getByText('理念 02')).toBeInTheDocument();
    expect(screen.getByText('理念 03')).toBeInTheDocument();
  });

  it('uses warm surfaces instead of abrupt black for the first step and first principle', () => {
    render(<AboutAutoWonderPage />);

    expect(screen.getByText('工单系统集成').closest('article')).toHaveStyle({
      background: 'linear-gradient(135deg, #fff7ed 0%, #ffedd5 100%)',
    });
    expect(screen.getByText('理念 01').closest('article')).toHaveStyle({
      background: 'linear-gradient(135deg, #fff7ed 0%, #fef3c7 100%)',
    });
  });
});
