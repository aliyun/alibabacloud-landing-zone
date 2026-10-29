import { Fragment, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Button, DatePicker, Modal, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { CloseCircleFilled, DeleteOutlined, FilterOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { formatDateTime } from '@/shared/i18n/format';
import { useMembers } from '@/features/settings/hooks';
import { useWorkitemTagOptions } from '../hooks';
import {
  EMPTY_CREATED_TIME_RANGE,
  buildCreatedTimeRangePreset,
  hasCreatedTimeRange,
  isCreatedTimeRangeInvalid,
  type CreatedTimeRangeDraft,
} from '../createdTimeFilter';
import {
  TAG_CONDITION_LIMIT,
  createEmptyTagCondition,
  hasIncompleteTagCondition,
  isTagConditionsTooLong,
  type WorkitemTagConditionDraft,
  type WorkitemTagConditionOp,
} from '../tagConditionFilter';

/** 高级筛选的完整条件：创建时间、创建者（本地用户 ID，多选之间为 OR）与标签逻辑条件（条件之间为 AND）。 */
export interface WorkitemAdvancedFilterState {
  created: CreatedTimeRangeDraft;
  creatorIds: number[];
  tagConditions: WorkitemTagConditionDraft[];
}

export const EMPTY_ADVANCED_FILTER: WorkitemAdvancedFilterState = {
  created: EMPTY_CREATED_TIME_RANGE,
  creatorIds: [],
  tagConditions: [],
};

const TAG_OP_LABEL_KEYS = {
  AND: 'list.tagConditionOpAnd',
  OR: 'list.tagConditionOpOr',
  IN: 'list.tagConditionOpIn',
  NOT: 'list.tagConditionOpNot',
} as const;

const TAG_OPS = ['AND', 'OR', 'IN', 'NOT'] as const;
const TAG_MORE_SUFFIX = '…';

interface WorkitemAdvancedFilterProps {
  applied: WorkitemAdvancedFilterState;
  onApply: (next: WorkitemAdvancedFilterState) => void;
}

function formatBound(value: Dayjs): string {
  return formatDateTime(value.toDate(), { year: 'numeric', month: '2-digit', day: '2-digit' });
}

/** 成员姓名缺失时回退到用户名，再回退到用户 ID，保证筛选条件始终可读。 */
function creatorName(member: { nickname: string; username: string } | undefined, id: number): string {
  if (!member) return `#${id}`;
  return member.nickname || member.username || `#${id}`;
}

/**
 * 工单页「高级筛选」入口：创建时间条件支持两侧独立设置与快捷范围；
 * 创建者条件从当前工作空间成员中多选，标签按成员姓名回显；
 * 标签条件逐行编辑运算符与标签，条件之间为 AND。
 * 条件不完整或超出长度上限时给出明确提示并禁用「应用」，非法查询不会提交。
 * 各条件的清除互不影响，「重置」同时清空。
 */
export function WorkitemAdvancedFilter({ applied, onApply }: WorkitemAdvancedFilterProps) {
  const { t, i18n } = useTranslation('workitem');
  const membersQuery = useMembers();
  const tagOptionsQuery = useWorkitemTagOptions();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<WorkitemAdvancedFilterState>(applied);
  const rowIdRef = useRef(1);

  const createdInvalid = isCreatedTimeRangeInvalid(draft.created);
  const tagConditionsIncomplete = hasIncompleteTagCondition(draft.tagConditions);
  const tagConditionsTooLong = isTagConditionsTooLong(draft.tagConditions);
  const invalid = createdInvalid || tagConditionsIncomplete || tagConditionsTooLong;
  const tagConditionLimitReached = draft.tagConditions.length >= TAG_CONDITION_LIMIT;
  const tagOptionsFailed = tagOptionsQuery.isError;
  const tagOptions = (tagOptionsQuery.data ?? []).map((tag) => ({ value: tag, label: tag }));

  const members = membersQuery.data ?? [];
  const memberOptions = members.map((member) => ({
    value: member.userId,
    label: creatorName(member, member.userId),
    searchText: [member.nickname, member.username, member.email].filter(Boolean).join(' '),
  }));

  const creatorNames = applied.creatorIds.map((id) => creatorName(members.find((item) => item.userId === id), id));
  const nameSeparator = i18n.language.startsWith('zh') ? '、' : ', ';
  const displayedNames = creatorNames.slice(0, 2).join(nameSeparator);
  const creatorTagText = creatorNames.length > 2
    ? t('list.creatorFilterMore', { names: displayedNames, total: creatorNames.length })
    : t('list.creatorFilterNames', { names: displayedNames });

  // 每次打开都从已应用条件起步，避免上次未提交的草稿残留
  const openPanel = () => {
    rowIdRef.current = applied.tagConditions.reduce((max, condition) => Math.max(max, condition.rowId), 0) + 1;
    setDraft(applied);
    setOpen(true);
  };

  const nextRowId = () => {
    const id = rowIdRef.current;
    rowIdRef.current += 1;
    return id;
  };

  const updateTagCondition = (
    rowId: number,
    patch: Partial<Pick<WorkitemTagConditionDraft, 'op' | 'tags'>>,
  ) => {
    setDraft((prev) => ({
      ...prev,
      tagConditions: prev.tagConditions.map((condition) => (
        condition.rowId === rowId ? { ...condition, ...patch } : condition
      )),
    }));
  };

  const removeTagCondition = (rowId: number) => {
    setDraft((prev) => ({
      ...prev,
      tagConditions: prev.tagConditions.filter((condition) => condition.rowId !== rowId),
    }));
  };

  const addTagCondition = () => {
    setDraft((prev) => (
      prev.tagConditions.length >= TAG_CONDITION_LIMIT
        ? prev
        : { ...prev, tagConditions: [...prev.tagConditions, createEmptyTagCondition(nextRowId())] }
    ));
  };

  const creatorTag = applied.creatorIds.length > 0 ? (
    <Tag
      data-testid="workitem-creator-filter-tag"
      closable
      closeIcon={<CloseCircleFilled data-testid="workitem-creator-filter-clear" aria-label={t('list.clearCreatorFilter')} />}
      onClose={() => onApply({ ...applied, creatorIds: [] })}
    >
      {creatorTagText}
    </Tag>
  ) : null;

  return (
    <Space size={4}>
      <Button icon={<FilterOutlined aria-hidden="true" />} onClick={openPanel}>
        {t('list.advancedFilter')}
      </Button>
      {hasCreatedTimeRange(applied.created) ? (
        <Tag
          data-testid="workitem-created-filter-tag"
          closable
          closeIcon={<CloseCircleFilled data-testid="workitem-created-filter-clear" aria-label={t('list.clearCreatedFilter')} />}
          onClose={() => onApply({ ...applied, created: EMPTY_CREATED_TIME_RANGE })}
        >
          {applied.created.from && applied.created.to
            ? t('list.createdFilterRange', { from: formatBound(applied.created.from), to: formatBound(applied.created.to) })
            : applied.created.from
              ? t('list.createdFilterFrom', { from: formatBound(applied.created.from) })
              : t('list.createdFilterTo', { to: formatBound(applied.created.to!) })}
        </Tag>
      ) : null}
      {creatorNames.length > 2
        ? <Tooltip title={creatorNames.join(nameSeparator)}>{creatorTag}</Tooltip>
        : creatorTag}
      {applied.tagConditions.map((condition) => {
        const opLabel = t(TAG_OP_LABEL_KEYS[condition.op]);
        const visibleTags = condition.tags.slice(0, 2).join(nameSeparator);
        const summary = t('list.tagConditionSummary', {
          op: opLabel,
          tags: condition.tags.length > 2 ? `${visibleTags}${TAG_MORE_SUFFIX}` : visibleTags,
        });
        const tag = (
          <Tag
            data-testid="workitem-tag-condition-tag"
            closable
            closeIcon={<CloseCircleFilled data-testid={`workitem-tag-condition-clear-${condition.rowId}`} aria-label={t('list.clearTagCondition')} />}
            onClose={() => onApply({
              ...applied,
              tagConditions: applied.tagConditions.filter((item) => item.rowId !== condition.rowId),
            })}
          >
            {summary}
          </Tag>
        );
        return condition.tags.length > 2 ? (
          <Tooltip
            key={condition.rowId}
            title={t('list.tagConditionSummary', { op: opLabel, tags: condition.tags.join(nameSeparator) })}
          >
            {tag}
          </Tooltip>
        ) : <Fragment key={condition.rowId}>{tag}</Fragment>;
      })}
      <Modal
        title={t('list.advancedFilter')}
        open={open}
        onCancel={() => setOpen(false)}
        destroyOnHidden
        width="min(760px, 92vw)"
        styles={{ body: { maxHeight: '70vh', overflowY: 'auto' } }}
        footer={[
          <Button key="reset" onClick={() => { setDraft(EMPTY_ADVANCED_FILTER); onApply(EMPTY_ADVANCED_FILTER); }}>
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
            <Button size="small" onClick={() => setDraft((prev) => ({ ...prev, created: buildCreatedTimeRangePreset('lastWeek') }))}>
              {t('list.createdPresetLastWeek')}
            </Button>
            <Button size="small" onClick={() => setDraft((prev) => ({ ...prev, created: buildCreatedTimeRangePreset('lastMonth') }))}>
              {t('list.createdPresetLastMonth')}
            </Button>
          </Space>
          <DatePicker
            format="YYYY-MM-DD"
            allowClear
            style={{ width: '100%' }}
            value={draft.created.from}
            placeholder={t('list.createdFromPlaceholder')}
            onChange={(value) => setDraft((prev) => ({ ...prev, created: { ...prev.created, from: value } }))}
          />
          <DatePicker
            format="YYYY-MM-DD"
            allowClear
            style={{ width: '100%' }}
            value={draft.created.to}
            placeholder={t('list.createdToPlaceholder')}
            onChange={(value) => setDraft((prev) => ({ ...prev, created: { ...prev.created, to: value } }))}
          />
          {createdInvalid ? <Alert type="error" showIcon message={t('list.createdRangeInvalid')} /> : null}
          <Typography.Text strong>{t('list.creatorLabel')}</Typography.Text>
          <Select
            mode="multiple"
            showSearch
            allowClear
            maxTagCount="responsive"
            style={{ width: '100%' }}
            placeholder={t('list.creatorPlaceholder')}
            aria-label={t('list.creatorLabel')}
            loading={membersQuery.isLoading}
            value={draft.creatorIds}
            onChange={(values: number[]) => setDraft((prev) => ({ ...prev, creatorIds: values }))}
            options={memberOptions}
            filterOption={(input, option) => String(option?.searchText ?? '')
              .toLowerCase()
              .includes(input.trim().toLowerCase())}
            notFoundContent={membersQuery.isError ? t('list.creatorLoadFailed') : t('list.creatorNoData')}
          />
          <Typography.Text strong>{t('list.tagConditionLabel')}</Typography.Text>
          <Typography.Text type="secondary">{t('list.tagConditionOpOrInHint')}</Typography.Text>
          {tagOptionsFailed ? <Alert type="warning" showIcon message={t('list.tagConditionOptionsLoadFailed')} /> : null}
          {draft.tagConditions.map((condition) => (
            <Space
              key={condition.rowId}
              data-testid={`workitem-tag-condition-row-${condition.rowId}`}
              size={4}
              align="start"
              style={{ width: '100%' }}
            >
              <Select<WorkitemTagConditionOp>
                style={{ width: 150 }}
                aria-label={t('list.tagConditionLabel')}
                value={condition.op}
                onChange={(op) => updateTagCondition(condition.rowId, { op })}
                options={TAG_OPS.map((op) => ({ value: op, label: t(TAG_OP_LABEL_KEYS[op]) }))}
              />
              <Select
                mode="multiple"
                showSearch
                allowClear
                maxTagCount="responsive"
                style={{ flex: 1, minWidth: 0 }}
                placeholder={t('list.tagConditionTagsPlaceholder')}
                aria-label={t('list.tagConditionTagsPlaceholder')}
                loading={tagOptionsQuery.isLoading}
                disabled={tagOptionsFailed}
                value={condition.tags}
                onChange={(tags: string[]) => updateTagCondition(condition.rowId, { tags })}
                options={tagOptions}
                notFoundContent={t('list.tagConditionNoOptions')}
              />
              <Button
                type="text"
                danger
                icon={<DeleteOutlined aria-hidden="true" />}
                aria-label={t('list.removeTagCondition')}
                onClick={() => removeTagCondition(condition.rowId)}
              />
            </Space>
          ))}
          <Space size={8} wrap>
            <Button size="small" onClick={addTagCondition} disabled={tagConditionLimitReached || tagOptionsFailed}>
              {t('list.addTagCondition')}
            </Button>
            {tagConditionLimitReached
              ? <Typography.Text type="secondary">{t('list.tagConditionLimitReached', { count: TAG_CONDITION_LIMIT })}</Typography.Text>
              : null}
          </Space>
          {tagConditionsIncomplete ? <Alert type="error" showIcon message={t('list.tagConditionIncomplete')} /> : null}
          {tagConditionsTooLong ? <Alert type="error" showIcon message={t('list.tagConditionTooLong')} /> : null}
        </Space>
      </Modal>
    </Space>
  );
}
