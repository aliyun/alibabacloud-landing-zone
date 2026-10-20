import { i18n } from '@/shared/i18n';
import { useTranslation } from 'react-i18next';
import { useId, type ReactNode } from 'react';
import { AuditOutlined, CheckCircleOutlined, CodeOutlined, CloudUploadOutlined, ExperimentOutlined, FileTextOutlined, PlayCircleOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { PageHeader } from '@/shared/ui/PageHeader';
import { AppearanceSwitch } from '@/shared/theme/AppearanceProvider';
import { HelpCenterLink } from '@/shared/ui/HelpCenterLink';
import { LanguageSwitch } from '@/shared/i18n/LanguageSwitch';
import './AuthEntryShell.css';

const returnPath = 'M600 80H624Q640 80 640 96V160Q640 180 620 180H20Q0 180 0 160V96Q0 80 16 80H40';

const deliverySteps = [
  { get label() { return i18n.t('auth:intro.clarification'); }, icon: <FileTextOutlined /> },
  { get label() { return i18n.t('auth:intro.start'); }, icon: <PlayCircleOutlined /> },
  { get label() { return i18n.t('auth:intro.develop'); }, icon: <CodeOutlined />, accent: true },
  { get label() { return i18n.t('auth:intro.verify'); }, icon: <SafetyCertificateOutlined />, accent: true },
  { get label() { return i18n.t('auth:intro.review'); }, icon: <AuditOutlined />, accent: true },
  { get label() { return i18n.t('auth:intro.deploy'); }, icon: <CloudUploadOutlined />, accent: true },
  { get label() { return i18n.t('auth:intro.test'); }, icon: <ExperimentOutlined />, accent: true },
  { get label() { return i18n.t('auth:intro.deliver'); }, icon: <CheckCircleOutlined />, accent: true },
];

export function AuthEntryShell({ children }: { children: ReactNode }) {
  const { t } = useTranslation('auth');
  const gridId = useId();
  return (
    <>
      <PageHeader actions={<><HelpCenterLink /><LanguageSwitch /><AppearanceSwitch /></>} />
      <main className="auth-entry-page">
        <section className="auth-entry-story" aria-label={t('intro.label')}>
          <div className="auth-entry-intro">
            <p className="auth-entry-eyebrow">{t('intro.eyebrow')}</p>
            <h1>{t('intro.title')}</h1>
          </div>
          <figure className="auth-delivery" aria-label={t('intro.diagram')}>
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
                  <span className="auth-delivery-node" aria-hidden="true">{icon}</span>
                  <span className={accent ? 'auth-delivery-label auth-delivery-label--accent' : 'auth-delivery-label'}>{label}</span>
                </li>
              ))}
            </ol>
            <figcaption>{t('intro.iterate')}</figcaption>
          </figure>
        </section>
        <section className="auth-entry-form-panel" aria-label={t('intro.account')}>{children}</section>
      </main>
    </>
  );
}
