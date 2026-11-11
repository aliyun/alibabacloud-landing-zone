import { useTranslation } from 'react-i18next';
import type { DiffRenderItem, DiffLineRow } from './lineDiff';

interface SplitDiffTableProps {
  items: DiffRenderItem[];
  baseLabel: string;
  targetLabel: string;
  blockIndexByRow: ReadonlyMap<number, number>;
  onExpandFold: (foldStart: number) => void;
}

const FOLD_ITEM_TYPE = 'fold' as const;

function rowClass(kind: DiffLineRow['kind']): string {
  return kind === 'add' ? 'awdiff-row awdiff-row-add' : kind === 'del' ? 'awdiff-row awdiff-row-del' : 'awdiff-row';
}

export function SplitDiffTable({
  items,
  baseLabel,
  targetLabel,
  blockIndexByRow,
  onExpandFold,
}: SplitDiffTableProps) {
  const { t } = useTranslation('agent');
  return (
    <table className="awdiff-table" data-testid="awdiff-split-table">
      <colgroup>
        <col className="awdiff-col-ln" />
        <col />
        <col className="awdiff-col-ln" />
        <col />
      </colgroup>
      <thead>
        <tr>
          <th colSpan={2} className="awdiff-th-base">{baseLabel}</th>
          <th colSpan={2}>{targetLabel}</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item, index) => {
          if (item.type === FOLD_ITEM_TYPE) {
            return (
              <tr className="awdiff-fold" key={`fold-${item.foldStart}-${index}`}>
                <td colSpan={4}>
                  <span>{t('diff.fold.hidden', { lines: item.hiddenCount })}</span>
                  <button type="button" className="awdiff-link" onClick={() => onExpandFold(item.foldStart)}>
                    {t('diff.action.expandContext')}
                  </button>
                </td>
              </tr>
            );
          }
          const { row, rowIndex } = item;
          const blockIndex = blockIndexByRow.get(rowIndex);
          return (
            <tr
              className={rowClass(row.kind)}
              key={`row-${rowIndex}`}
              data-block-index={blockIndex ?? undefined}
            >
              <td className="awdiff-ln">{row.leftNo ?? ''}</td>
              <td className="awdiff-code awdiff-code-left">{row.leftText}</td>
              <td className="awdiff-ln awdiff-ln-right">{row.rightNo ?? ''}</td>
              <td className="awdiff-code awdiff-code-right">{row.rightText}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
