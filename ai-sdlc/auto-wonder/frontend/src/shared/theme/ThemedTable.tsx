import { Table as AntTable, type TableProps } from 'antd';

/** Shared alignment contract: short values centered; long-text columns explicitly opt into left alignment. */
function alignColumns<T extends object>(columns: TableProps<T>['columns']): TableProps<T>['columns'] {
  return columns?.map(column => ({
    ...column,
    align: column.align ?? 'center',
    ...('children' in column ? {children: alignColumns(column.children)} : {}),
    onHeaderCell: (...args) => {
      const props = column.onHeaderCell?.(...args);
      return {...props, style: {...props?.style, textAlign: 'center'}};
    },
  }));
}
export function Table<T extends object>(props: TableProps<T>) {
  return <AntTable<T> {...props} columns={alignColumns(props.columns)} />;
}
