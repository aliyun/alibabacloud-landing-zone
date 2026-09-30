import type { ReactNode } from 'react';

export function PageHeading({ title, description, extra }: {
  title: ReactNode;
  description?: ReactNode;
  extra?: ReactNode;
}) {
  return (
    <div className="aw-page-heading">
      <div className="aw-page-heading-copy">
        <h1>{title}</h1>
        {description != null && <div className="aw-page-heading-description">{description}</div>}
      </div>
      {extra != null && <div className="aw-page-heading-extra">{extra}</div>}
    </div>
  );
}
