import type { ReactNode } from 'react';

/** 看板版块容器：标题（可带副标题）+ 左侧对齐的筛选工具条 + 卡片内容。 */
export function DashboardSection({ title, subtitle, toolbar, children }: {
  title: string;
  subtitle?: string;
  toolbar?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="space-dashboard-section">
      <div className="space-dashboard-section-head">
        <h2 className="space-dashboard-section-title">
          <span className="space-dashboard-section-rule" aria-hidden="true" />
          {title}
          {subtitle != null && <span className="space-dashboard-section-subtitle">{subtitle}</span>}
        </h2>
      </div>
      {toolbar != null && <div className="space-dashboard-toolbar">{toolbar}</div>}
      {children}
    </section>
  );
}
