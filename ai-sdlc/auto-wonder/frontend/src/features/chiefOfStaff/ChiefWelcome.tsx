import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { ChiefIcon } from './ChiefIcon';

interface ChiefWelcomeProps {
  disabled: boolean;
  onOptimize: () => void;
  onInitialize: () => void;
}

export function ChiefWelcome({ disabled, onOptimize, onInitialize }: ChiefWelcomeProps) {
  const { t } = useTranslation('chiefOfStaff');
  const id = useId();
  const scenarios = [{
    id: 'initialize',
    title: t('scenarios.initializeTitle'),
    description: t('scenarios.initializeDescription'),
    tags: [],
    icon: <path d="m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5" />,
    onSelect: onInitialize,
  }, {
    id: 'optimize',
    title: t('scenarios.optimizeTitle'),
    description: t('scenarios.optimizeDescription'),
    tags: [t('scenarios.efficiency'), t('scenarios.reliability'), t('scenarios.cost')],
    icon: <path d="M4 18V6m0 12h16M8 14l4-4 3 2 5-7m-5 0h5v5" />,
    onSelect: onOptimize,
  }];

  return <section className="aw-chief-welcome" aria-labelledby={`${id}-heading`}>
    <div className="aw-chief-welcome-intro">
      <div className="aw-chief-welcome-mark"><ChiefIcon size={29} /></div>
      <h3 id={`${id}-heading`}>{t('welcome')}</h3>
      <p>{t('welcomeDescription')}</p>
    </div>
    <h4 className="aw-chief-scenarios-heading">{t('scenarios.heading')}</h4>
    <div className="aw-chief-scenarios">
      {scenarios.map(scenario => <button key={scenario.id} type="button" className="aw-chief-scenario"
        aria-label={scenario.title} aria-describedby={`${id}-${scenario.id}-description`}
        disabled={disabled} onClick={scenario.onSelect}>
        <span className="aw-chief-scenario-icon" aria-hidden="true">
          <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">{scenario.icon}</svg>
        </span>
        <span className="aw-chief-scenario-title">{scenario.title}</span>
        <svg className="aw-chief-scenario-arrow" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12h14m-5-5 5 5-5 5" /></svg>
        <span className="aw-chief-scenario-description" id={`${id}-${scenario.id}-description`}>{scenario.description}</span>
        {scenario.tags.length > 0 && <span className="aw-chief-scenario-tags" aria-hidden="true">{scenario.tags.map(tag => <span key={tag}>{tag}</span>)}</span>}
      </button>)}
    </div>
    <p className="aw-chief-welcome-note">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 3 4 6v6c0 5 8 9 8 9s8-4 8-9V6l-8-3Z" /><path d="m9 12 2 2 4-4" /></svg>
      <span>{t('scenarios.confirmationNote')}</span>
    </p>
  </section>;
}
