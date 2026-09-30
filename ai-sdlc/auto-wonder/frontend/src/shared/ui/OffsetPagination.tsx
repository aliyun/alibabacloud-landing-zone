import { Pagination } from 'antd';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';

interface OffsetPaginationProps {
  /** localStorage 记忆键（每页条数） */
  storageKey: string;
  offset: number;
  /** 当前页实际返回的条数，用于判断是否还有下一页 */
  loadedCount: number;
  onOffsetChange: (offset: number) => void;
  /** 每页条数变化时回调（调用方需把查询 size 换成新值并回到第一页） */
  onPageSizeChange?: (size: number) => void;
  pageSizeOptions?: readonly number[];
  defaultPageSize?: number;
}

/**
 * offset 式接口（无 total）共用的分页条，观感与全站 antd 表格分页一致：
 * 页码 + 每页条数选择器，每页条数本地记忆。
 * 接口不返回总数，无法展示「共 X 条」；满一页时假定还有下一页，
 * 页码随翻页增长，翻过头会看到空页，返回即恢复。
 */
export function OffsetPagination({
  storageKey, offset, loadedCount, onOffsetChange, onPageSizeChange,
  pageSizeOptions = [10, 20, 50], defaultPageSize = 10,
}: OffsetPaginationProps) {
  const [pageSize, setPageSize] = usePageSizePreference(storageKey, pageSizeOptions, defaultPageSize);
  const total = offset + loadedCount + (loadedCount >= pageSize ? pageSize : 0);
  return (
    <Pagination
      className="ant-table-pagination ant-table-pagination-right aw-offset-pagination"
      current={Math.floor(offset / pageSize) + 1}
      pageSize={pageSize}
      total={total}
      showSizeChanger
      onChange={(page, nextSize) => {
        if (nextSize !== pageSize) {
          setPageSize(nextSize);
          onPageSizeChange?.(nextSize);
          onOffsetChange(0);
        } else {
          onOffsetChange((page - 1) * pageSize);
        }
      }}
    />
  );
}
