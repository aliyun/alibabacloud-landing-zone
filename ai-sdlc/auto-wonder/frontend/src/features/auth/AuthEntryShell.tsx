import { useId, type ReactNode } from 'react';
import { AuditOutlined, CheckCircleOutlined, CodeOutlined, CloudUploadOutlined, ExperimentOutlined, FileTextOutlined, PlayCircleOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { PageHeader } from '@/shared/ui/PageHeader';
import { AppearanceSwitch } from '@/shared/theme/AppearanceProvider';
import { HelpCenterLink } from '@/shared/ui/HelpCenterLink';
import './AuthEntryShell.css';

const returnPath = 'M600 80H624Q640 80 640 96V160Q640 180 620 180H20Q0 180 0 160V96Q0 80 16 80H40';

const deliverySteps = [
  { label: '需求澄清', icon: <FileTextOutlined /> },
  { label: '启动', icon: <PlayCircleOutlined /> },
  { label: '开发', icon: <CodeOutlined />, accent: true },
  { label: '验证', icon: <SafetyCertificateOutlined />, accent: true },
  { label: '评审', icon: <AuditOutlined />, accent: true },
  { label: '部署', icon: <CloudUploadOutlined />, accent: true },
  { label: '测试', icon: <ExperimentOutlined />, accent: true },
  { label: '交付', icon: <CheckCircleOutlined />, accent: true },
];

export function AuthEntryShell({ children }: { children: ReactNode }) {
  const gridId = useId();
  return (
    <>
      <PageHeader actions={<><HelpCenterLink /><AppearanceSwitch /></>} />
      <main className="auth-entry-page">
        <section className="auth-entry-story" aria-label="平台介绍">
          <div className="auth-entry-intro">
            <p className="auth-entry-eyebrow">AI Native SDLC Platform</p>
            <h1>软件产品自动交付平台</h1>
          </div>
          <figure className="auth-delivery" aria-label="软件自动交付流程示意：需求澄清、启动、开发、验证、评审、部署、测试、交付，持续迭代">
            <svg className="auth-delivery-lines" viewBox="0 0 640 240" fill="none" aria-hidden="true">
              <defs>
                <pattern id={gridId} width="20" height="20" patternUnits="userSpaceOnUse">
                  <circle cx="10" cy="10" r=".7" fill="var(--aw-border)" />
                </pattern>
              </defs>
              <rect x="-20" y="0" width="680" height="240" fill={`url(#${gridId})`} />
              <path className="auth-delivery-track" d="M40 80H600" />
              <path className="auth-delivery-return" d={returnPath} />
              <path className="auth-delivery-flow" pathLength="100" d="M40 80H600" />
              <path className="auth-delivery-flow auth-delivery-flow--return" pathLength="100" d={returnPath} />
            </svg>
            <ol className="auth-delivery-steps">
              {deliverySteps.map(({ label, icon, accent }) => (
                <li key={label}>
                  <span className="auth-delivery-node">{icon}</span>
                  <span className={accent ? 'auth-delivery-label auth-delivery-label--accent' : 'auth-delivery-label'}>{label}</span>
                </li>
              ))}
            </ol>
            <figcaption>持续迭代</figcaption>
          </figure>
        </section>
        <section className="auth-entry-form-panel" aria-label="账号入口">{children}</section>
      </main>
    </>
  );
}
