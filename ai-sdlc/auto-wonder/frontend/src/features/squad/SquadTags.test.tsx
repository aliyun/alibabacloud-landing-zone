import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { SquadTags } from './SquadTags';
import { UNSQUADED_LABEL } from './squadGrouping';

function renderTags(props: ComponentProps<typeof SquadTags>) {
  return render(<MemoryRouter><SquadTags {...props} /></MemoryRouter>);
}

describe('SquadTags', () => {
  it('renders one link per squad the resource belongs to', () => {
    renderTags({ squadIds: [7, 8], squadNames: ['前端小队', '测试小队'] });

    const frontend = screen.getByRole('link', { name: '前端小队' });
    expect(frontend).toHaveAttribute('href', '/squads?squadId=7');
    expect(screen.getByRole('link', { name: '测试小队' }))
      .toHaveAttribute('href', '/squads?squadId=8');
  });

  it('falls back to the squad id when a name could not be resolved', () => {
    renderTags({ squadIds: [7, 42], squadNames: ['前端小队'] });

    expect(screen.getByRole('link', { name: '前端小队' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '小队 #42' }))
      .toHaveAttribute('href', '/squads?squadId=42');
  });

  it('labels unaffiliated resources instead of rendering nothing', () => {
    const { rerender } = renderTags({ squadIds: [], squadNames: [] });
    expect(screen.getByText(UNSQUADED_LABEL)).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();

    rerender(<MemoryRouter><SquadTags squadIds={null} squadNames={null} /></MemoryRouter>);
    expect(screen.getByText(UNSQUADED_LABEL)).toBeInTheDocument();

    rerender(<MemoryRouter><SquadTags /></MemoryRouter>);
    expect(screen.getByText(UNSQUADED_LABEL)).toBeInTheDocument();
  });

  it('keeps ids and names index aligned when names are shorter than ids', () => {
    renderTags({ squadIds: [7, 8, 9], squadNames: ['唯一'] });

    expect(screen.getByRole('link', { name: '唯一' })).toHaveAttribute('href', '/squads?squadId=7');
    expect(screen.getByRole('link', { name: '小队 #8' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '小队 #9' })).toBeInTheDocument();
  });
});
