import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Button, DatePicker, Modal, Space, Tag, Typography } from 'antd';
import { CloseCircleFilled, FilterOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { formatDateTime } from '@/shared/i18n/format';
import {
  EMPTY_CREATED_TIME_RANGE,
  buildCreatedTimeRangePreset,
  hasCreatedTimeRange,
  isCreatedTimeRangeInvalid,
  type CreatedTimeRangeDraft,
} from '../createdTimeFilter';

interface WorkitemAdvancedFilterProps {
  applied: CreatedTimeRangeDraft;
  onApply: (range: CreatedTimeRangeDraft) => void;
}

function formatBound(value: Dayjs): string {
  return formatDateTime(value.toDate(), { year: 'numeric', month: '2-digit', day: '2-digit' });
}

/**
 * 工单页「高级筛选」入口（工单 #55696）：首期只提供创建时间条件，
 * 面板内两侧日期可独立设置；起始晚于结束时给出明确提示并禁用「应用」，非法查询不会提交。
 * 「近一周」「近一个月」快捷范围一键回填起止日期，回填后仍可微调。
 * 已验证的已选条件以标签形式回显在筛选栏，可一键清除。
 */
export function WorkitemAdvancedFilter({ applied, onApply }: WorkitemAdvancedFilterProps) {
  const { t } = useTranslation('workitem');
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<CreatedTimeRangeDraft>(applied);
  const invalid = isCreatedTimeRangeInvalid(draft);

  // 每次打开都从已应用条件起步，避免上次未提交的草稿残留
  const openPanel = () => {
    setDraft(applied);
    setOpen(true);
  };

  return (
    <Space size={4}>
      <Button icon={<FilterOutlined aria-hidden="true" />} onClick={openPanel}>
        {t('list.advancedFilter')}
      </Button>
      {hasCreatedTimeRange(applied) ? (
        <Tag
          data-testid="workitem-created-filter-tag"
          closable
          closeIcon={<CloseCircleFilled data-testid="workitem-created-filter-clear" aria-label={t('list.clearCreatedFilter')} />}
          onClose={() => onApply(EMPTY_CREATED_TIME_RANGE)}
        >
          {applied.from && applied.to
            ? t('list.createdFilterRange', { from: formatBound(applied.from), to: formatBound(applied.to) })
            : applied.from
              ? t('list.createdFilterFrom', { from: formatBound(applied.from) })
              : t('list.createdFilterTo', { to: formatBound(applied.to!) })}
        </Tag>
      ) : null}
      <Modal
        title={t('list.advancedFilter')}
        open={open}
        onCancel={() => setOpen(false)}
        destroyOnHidden
        width={420}
        footer={[
          <Button key="reset" onClick={() => { setDraft(EMPTY_CREATED_TIME_RANGE); onApply(EMPTY_CREATED_TIME_RANGE); }}>
            {t('list.resetAdvancedFilter')}
          </Button>,
          <Button key="cancel" onClick={() => setOpen(false)}>{t('list.cancel')}</Button>,
          <Button key="apply" type="primary" disabled={invalid} onClick={() => { onApply(draft); setOpen(false); }}>
            {t('list.applyAdvancedFilter')}
          </Button>,
        ]}
      >
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Typography.Text strong>{t('list.createdTimeLabel')}</Typography.Text>
          <Space size={4} wrap>
            <Button size="small" onClick={() => setDraft(buildCreatedTimeRangePreset('lastWeek'))}>
              {t('list.createdPresetLastWeek')}
            </Button>
            <Button size="small" onClick={() => setDraft(buildCreatedTimeRangePreset('lastMonth'))}>
              {t('list.createdPresetLastMonth')}
            </Button>
          </Space>
          <DatePicker
            format="YYYY-MM-DD"
            allowClear
            style={{ width: '100%' }}
            value={draft.from}
            placeholder={t('list.createdFromPlaceholder')}
            onChange={(value) => setDraft((prev) => ({ ...prev, from: value }))}
          />
          <DatePicker
            format="YYYY-MM-DD"
            allowClear
            style={{ width: '100%' }}
            value={draft.to}
            placeholder={t('list.createdToPlaceholder')}
            onChange={(value) => setDraft((prev) => ({ ...prev, to: value }))}
          />
          {invalid ? <Alert type="error" showIcon message={t('list.createdRangeInvalid')} /> : null}
        </Space>
      </Modal>
    </Space>
  );
}
