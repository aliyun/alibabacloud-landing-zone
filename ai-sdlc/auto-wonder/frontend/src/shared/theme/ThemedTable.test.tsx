import { render, screen, fireEvent } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { Table } from './ThemedTable';

it('centers headers and short columns, preserves long-text alignment and table callbacks', () => {
  const click = vi.fn();
  render(<Table pagination={false} rowKey="id" dataSource={[{id: 1, title: 'A long workitem title'}]} columns={[
    {title: 'ID', dataIndex: 'id', onHeaderCell: () => ({onClick: click, style: {whiteSpace: 'nowrap', textAlign: 'left'}})},
    {title: 'Details', children: [{title: 'Title', dataIndex: 'title', align: 'left'}]},
  ]} />);
  expect(screen.getByRole('columnheader', {name: 'ID'})).toHaveStyle({textAlign: 'center', whiteSpace: 'nowrap'});
  expect(screen.getByRole('columnheader', {name: 'Title'})).toHaveStyle({textAlign: 'center'});
  expect(screen.getByRole('cell', {name: '1'})).toHaveStyle({textAlign: 'center'});
  expect(screen.getByRole('cell', {name: 'A long workitem title'})).toHaveStyle({textAlign: 'left'});
  fireEvent.click(screen.getByRole('columnheader', {name: 'ID'}));
  expect(click).toHaveBeenCalledOnce();
});
