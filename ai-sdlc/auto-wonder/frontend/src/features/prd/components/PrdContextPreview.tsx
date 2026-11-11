import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { usePrdVersions, usePrdVersion } from '../hooks';
import { PrdHtmlPreview, PRESET_VIEWPORTS } from './PrdHtmlPreview';
import { PrdPrototypePreview, prototypePages } from './PrdPrototypePreview';

/** A design is part of the requirement context, not another workitem card. */
export function PrdContextPreview({ workitemId }: { workitemId: string }) {
  const { t } = useTranslation('prd');
  const [open, setOpen] = useState(false);
  const { data: versions } = usePrdVersions(workitemId, open);
  const latest = versions?.slice().sort((a, b) => b.versionNo - a.versionNo)[0];
  const { data: version } = usePrdVersion(workitemId, open ? latest?.id ?? null : null);
  const pages = useMemo(() => {
    try { return prototypePages(JSON.parse(version?.designJson || '{}')); } catch { return []; }
  }, [version?.designJson]);
  return <details onToggle={event => setOpen(event.currentTarget.open)} style={{ marginBlock: 12 }}>
    <summary style={{ cursor: 'pointer' }}>{t('entry.designResult')}</summary>
    <Link to={`/workitems/${workitemId}/design`}>{t('entry.continueDesign')}</Link>
    {open && latest && (pages.length ? <PrdPrototypePreview pages={pages} /> : <PrdHtmlPreview workitemId={workitemId} versionId={latest.id} viewport={PRESET_VIEWPORTS.FIT} />)}
  </details>;
}
