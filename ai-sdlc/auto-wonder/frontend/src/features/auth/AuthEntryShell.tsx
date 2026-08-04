import type { ReactNode } from 'react';
import './AuthEntryShell.css';

interface AuthEntryShellProps {
  children: ReactNode;
}

const stats = [
  { value: '6', label: '上手步骤' },
  { value: '3', label: 'Agent 角色' },
  { value: '∞', label: '记忆沉淀' },
];

const workflow = [
  { title: '工单系统集成', tone: 'dark' },
  { title: '托管仓库', tone: 'light' },
  { title: '绑定 SDLC 小队', tone: 'orange' },
  { title: '沉淀记忆与产物', tone: 'green' },
];

export function AuthEntryShell({ children }: AuthEntryShellProps) {
  return (
    <div className="auth-entry-page">
      <section className="auth-entry-story" aria-label="AutoWonder 产品理念">
        <div>
          <div className="auth-entry-eyebrow">AutoWonder · Agent SDLC</div>
          <h1>登录后，把工单交给数字员工小队</h1>
          <p>工单系统、仓库、SDLC、执行器和组织记忆在入口第一屏形成完整认知。</p>
        </div>

        <div className="auth-entry-stats" aria-label="AutoWonder 能力摘要">
          {stats.map((stat) => (
            <div className="auth-entry-stat-card" key={stat.label}>
              <b>{stat.value}</b>
              <span>{stat.label}</span>
            </div>
          ))}
        </div>

        <div className="auth-entry-workflow" aria-label="AutoWonder 交付闭环">
          {workflow.map((item, index) => (
            <div className={`auth-entry-workflow-card auth-entry-workflow-${item.tone}`} key={item.title}>
              <span>{String(index + 1).padStart(2, '0')}</span>
              {item.title}
            </div>
          ))}
        </div>
      </section>

      <section className="auth-entry-form-panel">
        {children}
      </section>
    </div>
  );
}
