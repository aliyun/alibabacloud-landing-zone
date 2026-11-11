import { useTranslation } from 'react-i18next';
import type { DiffRenderItem, DiffLineRow } from './lineDiff';

interface UnifiedDiffTableProps {
  items: DiffRenderItem[];
  targetLabel: string;
  blockIndexByRow: ReadonlyMap<number, number>;
  onExpandFold: (foldStart: number) => void;
}

const FOLD_ITEM_TYPE = 'fold' as const;

function unifiedRow(row: DiffLineRow): { kind: DiffLineRow['kind']; lineNo: number | null; text: string } {
  if (row.kind === 'del') return { kind: 'del', lineNo: row.leftNo, text: row.leftText };
  return { kind: row.kind, lineNo: row.rightNo, text: row.rightText };
}

export function UnifiedDiffTable({
  items,
  targetLabel,
  blockIndexByRow,
  onExpandFold,
}: UnifiedDiffTableProps) {
  const { t } = useTranslation('agent');
  return (
    <table className="awdiff-table awdiff-unified-table" data-testid="awdiff-unified-table">
      <colgroup>
        <col className="awdiff-col-ln" />
        <col />
      </colgroup>
      <thead>
        <tr>
          <th colSpan={2}>{targetLabel}</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item, index) => {
          if (item.type === FOLD_ITEM_TYPE) {
            return (
              <tr className="awdiff-fold" key={`fold-${item.foldStart}-${index}`}>
                <td colSpan={2}>
                  <span>{t('diff.fold.hidden', { lines: item.hiddenCount })}</span>
                  <button type="button" className="awdiff-link" onClick={() => onExpandFold(item.foldStart)}>
                    {t('diff.action.expandContext')}
                  </button>
                </td>
              </tr>
            );
          }
          const { kind, lineNo, text } = unifiedRow(item.row);
          const blockIndex = blockIndexByRow.get(item.rowIndex);
          return (
            <tr
              className={kind === 'add' ? 'awdiff-row awdiff-row-add' : kind === 'del' ? 'awdiff-row awdiff-row-del' : 'awdiff-row'}
              key={`row-${item.rowIndex}`}
              data-block-index={blockIndex ?? undefined}
            >
              <td className="awdiff-ln">{lineNo ?? ''}</td>
              <td className="awdiff-code">{text}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
