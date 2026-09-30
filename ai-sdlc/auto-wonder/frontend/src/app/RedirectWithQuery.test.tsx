import { describe, it, expect } from 'vitest';
import type { ReactElement } from 'react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { RedirectWithQuery } from './RedirectWithQuery';

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="redirect-target">{location.pathname}{location.search || '(empty)'}</span>;
}

function renderRedirect(
  initialEntry: string,
  redirect: ReactElement,
  targetPath = '/agents',
) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/squads" element={redirect} />
        <Route path="/agents/reviews" element={redirect} />
        <Route path="/repos/map" element={redirect} />
        <Route path={targetPath} element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('RedirectWithQuery', () => {
  it('keeps the incoming query while forcing the hub tab, e.g. /squads?squadId=7 deep links', () => {
    renderRedirect('/squads?squadId=7', <RedirectWithQuery to="/agents" tab="squads" />);
    expect(screen.getByTestId('redirect-target')).toHaveTextContent('/agents?tab=squads&squadId=7');
  });

  it('targets the bare hub route when the legacy route has no query', () => {
    renderRedirect('/repos/map', <RedirectWithQuery to="/repos" tab="map" />, '/repos');
    expect(screen.getByTestId('redirect-target')).toHaveTextContent('/repos?tab=map');
  });

  it('preserves unrelated query params even without a forced tab', () => {
    renderRedirect('/agents/reviews?foo=1', <RedirectWithQuery to="/agents" />);
    expect(screen.getByTestId('redirect-target')).toHaveTextContent('/agents?foo=1');
  });
});
