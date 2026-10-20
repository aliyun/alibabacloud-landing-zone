import { useTranslation } from 'react-i18next';
import { useLocalizedValidation } from '@/shared/i18n/useLocalizedValidation';
import { formatDateTime as formatSharedDateTime, formatNumber } from '@/shared/i18n/format';
import { i18n } from '@/shared/i18n';
import { PageHeading } from '@/shared/ui/PageHeading';
import { usePageSizePreference } from '@/shared/lib/usePageSizePreference';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { AriaAttributes, Key, ReactNode } from 'react';
import {
  Card, Collapse, Tag, Button, Space, Segmented, Modal, Form, Input, Select, Popconfirm, message,
  Radio, Alert, Typography, Descriptions, Divider, InputNumber, Checkbox, Tooltip, Tree, TreeSelect, Spin, Switch, Empty} from 'antd';
import { Table } from '@/shared/theme/ThemedTable';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, FolderOpenOutlined, FileTextOutlined,
  CloseCircleFilled, MinusCircleOutlined, DownloadOutlined, ApartmentOutlined,
} from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listSkills, createSkill, updateSkill, deleteSkill, createSkillFromPackage, updateSkillPackage,
  testSkillConnection, getSkillPackageFiles, getSkillPackageFile, downloadSkillPackage, inspectSkillPackage,
  listCategories, createCategory, updateCategory, deleteCategory, setSkillCategory,
  batchSetSkillCategory, listAllSkills,
} from './api';
import type {
  Skill, SkillConnectionTestResult, SkillPackageFile, SkillPackageFileContent,
  Category, BatchSkillCategoryResult,
} from './api';
import type { ColumnsType } from 'antd/es/table';
import type { CheckboxProps } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { buildDirectoryZip, buildPackageTree, formatBytes } from './skillPackage';
import { MarkdownView } from '@/shared/ui/MarkdownView';
import { EllipsisText } from '@/shared/ui/EllipsisText';
import { useAccessCommand } from '@/shared/auth/useAccessCommand';
import { useAuthStore } from '@/shared/auth/store';
import { listExecutors } from '@/features/executor/api';
import type { ExecutorVO } from '@/features/executor/api';
import {
  buildCategoryTree, buildCategorySkillGroups, categoryMoveExclusions,
} from './categoryTree';
import type { CategoryNode, CategorySkillGroup } from './categoryTree';
import {
  isCategoryGroupingEnabled, setCategoryGroupingEnabled,
} from './categoryDisplayPreference';

function skillTypeLabel(type: string): string {
  switch (type) {
    case 'MCP': return i18n.t('skill:types.mcp');
    case 'SKILL': return i18n.t('skill:types.skill');
    case 'PLUGIN': return i18n.t('skill:types.plugin');
    case 'HOOK': return i18n.t('skill:types.hook');
    default: return type;
  }
}

const typeColor: Record<string, string> = {
  MCP: 'blue', SKILL: 'green', PLUGIN: 'orange', HOOK: 'purple',
};

const MAX_SKILL_PACKAGE_BYTES = 100 * 1024 * 1024;
const AUTHORIZATION_MASK = '********';

// antd Select 对 null/undefined 值会按空值处理，分类下拉用字符串哨兵表示「未分类」，
// 提交时再换算回显式 null（取消打标）。
const UNCATEGORIZED = 'none';

// useQuery 未决/失败时的兜底必须是稳定引用：若每次渲染都新建 []，
// 依赖 categories 的 useEffect 会每轮都触发 setState，形成无限重渲染。
const NO_CATEGORIES: Category[] = [];

function accessLabel(record: Skill) {
  if (record.sourceType === 'OSS_ZIP') {
    if (record.type === 'HOOK') return i18n.t('skill:access.hook');
    return i18n.t('skill:access.package');
  }
  if (record.type === 'SKILL') {
    return i18n.t('skill:access.builtin');
  }
  return i18n.t('skill:access.command');
}

/** 与后端 packageRef 的前置校验保持一致：只有存在 packageOssRef 的 OSS_ZIP 才有包可看。 */
function isPackageSkill(skill: Skill | null): skill is Skill {
  return !!skill && skill.sourceType === 'OSS_ZIP' && !!skill.packageOssRef;
}

function isMarkdown(path: string): boolean {
  return /\.(md|markdown)$/i.test(path);
}

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message ? error.message : i18n.t('skill:errors.operation');
}

export function SkillListPage() {
  const { t } = useTranslation('skill');
  const filterTypeOptions = [
    { value: '', label: t('all') },
    { value: 'SKILL', label: t('types.skill') },
    { value: 'MCP', label: t('types.mcp') },
    { value: 'HOOK', label: t('types.hook') },
  ];

  const creatableTypeOptions = [
    { value: 'SKILL', label: t('types.skill') },
    { value: 'MCP', label: t('types.mcp') },
    { value: 'HOOK', label: t('types.hook') },
  ];

  const queryClient = useQueryClient();
  const runWithAccess = useAccessCommand();
  const [page, setPage] = useState(1);
  const [size, setSize] = usePageSizePreference('autowonder.skills.pageSize', [10, 20, 50, 100], 10);
  const [typeFilter, setTypeFilter] = useState('');

  const [formOpen, setFormOpen] = useState(false);
  const [editingSkill, setEditingSkill] = useState<Skill | null>(null);
  const [detailSkill, setDetailSkill] = useState<Skill | null>(null);
  const [pendingDeleteId, setPendingDeleteId] = useState<number | null>(null);
  const [connectionResults, setConnectionResults] = useState<Record<number, SkillConnectionTestResult>>({});
  const [toolListResult, setToolListResult] = useState<SkillConnectionTestResult | null>(null);
  const [testingSkillId, setTestingSkillId] = useState<number | null>(null);
  const [testTargetSkill, setTestTargetSkill] = useState<Skill | null>(null);
  const [testExecutorId, setTestExecutorId] = useState<number | undefined>();
  const [accessMode, setAccessMode] = useState<'manual' | 'package'>('manual');
  const [packageReading, setPackageReading] = useState(false);
  const packageSelectionSeq = useRef(0);
  const [zipFile, setZipFile] = useState<File | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const zipInputRef = useRef<HTMLInputElement | null>(null);
  const [form] = Form.useForm();
  useLocalizedValidation(form);
  const selectedType = Form.useWatch('type', form);

  const [packageFiles, setPackageFiles] = useState<SkillPackageFile[]>([]);
  const [packageFilesLoading, setPackageFilesLoading] = useState(false);
  const [packageFilesError, setPackageFilesError] = useState<{ message?: string } | null>(null);
  const [selectedPackagePath, setSelectedPackagePath] = useState<string | null>(null);
  const [packageFileContent, setPackageFileContent] = useState<SkillPackageFileContent | null>(null);
  const [packageFileLoading, setPackageFileLoading] = useState(false);
  const [downloadingPackage, setDownloadingPackage] = useState(false);
  // 单文件内容请求的自增序号：用于作废在途响应，避免跨技能同名文件（如 SKILL.md）乱序返回时渲染错内容
  const packageFileRequestSeq = useRef(0);

  const workspaceId = useAuthStore((s) => s.currentWorkspace?.id ?? null);
  const currentUserId = useAuthStore((s) => s.user?.id ?? null);
  // 「按分类展示」按用户 + 项目维度持久化；默认关闭，保持原有平铺表格
  const [groupByCategory, setGroupByCategory] = useState(
    () => isCategoryGroupingEnabled(workspaceId, currentUserId),
  );
  const [categoryManageOpen, setCategoryManageOpen] = useState(false);
  const [editingCategoryId, setEditingCategoryId] = useState<number | null>(null);
  const [pendingDeleteCategoryId, setPendingDeleteCategoryId] = useState<number | null>(null);
  const [categoryExpandedKeys, setCategoryExpandedKeys] = useState<Key[]>([]);
  const [categoryForm] = Form.useForm();
  useLocalizedValidation(categoryForm);
  const [selectedRowKeys, setSelectedRowKeys] = useState<Key[]>([]);
  const [batchCategoryOpen, setBatchCategoryOpen] = useState(false);
  const [batchCategoryValue, setBatchCategoryValue] = useState<string>(UNCATEGORIZED);
  const [batchApplying, setBatchApplying] = useState(false);
  const [savingCategoryIds, setSavingCategoryIds] = useState<number[]>([]);
  const [activeCategorySkillId, setActiveCategorySkillId] = useState<number | null>(null);
  useEffect(() => {
    setActiveCategorySkillId(null);
  }, [workspaceId, currentUserId, page, typeFilter, groupByCategory]);

  // 切换项目 / 账号后重新读取各自的展示偏好
  useEffect(() => {
    setGroupByCategory(isCategoryGroupingEnabled(workspaceId, currentUserId));
  }, [workspaceId, currentUserId]);

  const { data, isLoading } = useQuery({
    queryKey: ['skills', page, size, typeFilter],
    queryFn: () => listSkills({ page, size, type: typeFilter || undefined }),
    enabled: !groupByCategory,
  });
  const skills = data?.list ?? [];
  const total = data?.total ?? 0;
  const { data: executors = [] } = useQuery<ExecutorVO[]>({ queryKey: ['executors'], queryFn: () => listExecutors() });

  // 分类数据供打标下拉、管理弹窗与分组视图共用；页面挂载即拉取（只读接口）
  const { data: categories = NO_CATEGORIES } = useQuery<Category[]>({ queryKey: ['categories'], queryFn: () => listCategories() });
  // 分组视图按完整筛选结果分组，单独翻页取全量；平铺视图仍走分页查询
  const { data: groupedSkills = [], isLoading: groupedSkillsLoading } = useQuery({
    queryKey: ['skills', 'all', typeFilter],
    queryFn: () => listAllSkills(typeFilter || undefined),
    enabled: groupByCategory,
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['skills'] });
  const invalidateCategories = () => queryClient.invalidateQueries({ queryKey: ['categories'] });

  const categoryTreeData = useMemo(() => buildCategoryTree(categories), [categories]);
  const categoryGrouping = useMemo(
    () => buildCategorySkillGroups(categoryTreeData, groupedSkills),
    [categoryTreeData, groupedSkills],
  );
  const categorySelectOptions = useMemo(() => [
    { value: UNCATEGORIZED, label: i18n.t('skill:uncategorized') },
    ...categories.map((category) => ({
      value: String(category.id),
      label: category.path || category.name,
    })),
  ], [categories, t]);

  const categorySelectTree = useMemo(() => {
    interface SelectTreeNode {
      value: string;
      title: string;
      path: string;
      children?: SelectTreeNode[];
    }
    const toSelectTree = (nodes: CategoryNode[]): SelectTreeNode[] => nodes.map((node) => ({
      value: String(node.id),
      title: node.name,
      path: node.path || node.name,
      children: node.children.length ? toSelectTree(node.children) : undefined,
    }));
    return toSelectTree(categoryTreeData);
  }, [categoryTreeData]);

  const startCreateCategory = (parentId?: number | null) => {
    setEditingCategoryId(null);
    setPendingDeleteCategoryId(null);
    categoryForm.resetFields();
    categoryForm.setFieldsValue({
      parentId: parentId == null ? UNCATEGORIZED : String(parentId),
    });
  };

  const editCategory = (id: number) => {
    const category = categories.find((item) => item.id === id);
    if (!category) return;
    setEditingCategoryId(id);
    setPendingDeleteCategoryId(null);
    categoryForm.setFieldsValue({
      name: category.name,
      parentId: category.parentId == null ? UNCATEGORIZED : String(category.parentId),
      description: category.description || '',
    });
  };

  // 修改分类时上级选项要排除自身及其后代，防止移动到自己的子树下形成环
  const parentCategoryOptions = useMemo(() => {
    const blocked = editingCategoryId != null
      ? categoryMoveExclusions(categories, editingCategoryId)
      : new Set<number>();
    const filterNodes = (nodes: typeof categorySelectTree): typeof categorySelectTree => nodes
      .filter((node) => !blocked.has(Number(node.value)))
      .map((node) => ({
        ...node,
        children: node.children ? filterNodes(node.children) : undefined,
      }));
    return [
      { value: UNCATEGORIZED, title: i18n.t('skill:category.top'), path: i18n.t('skill:category.top') },
      ...filterNodes(categorySelectTree),
    ];
  }, [categories, categorySelectTree, editingCategoryId, t]);

  // 分类树变更（新增/删除/移动）后默认全部展开，方便继续维护
  useEffect(() => {
    setCategoryExpandedKeys(categories.map((category) => category.id));
  }, [categories]);

  // 分类目录保留纯数据，选中行通过 titleRender 展示删除入口。
  const manageTreeData = useMemo(() => {
    const toTreeData = (nodes: CategoryNode[]): DataNode[] => nodes.map((node) => ({
      key: node.id,
      title: node.name,
      children: node.children.length > 0 ? toTreeData(node.children) : undefined,
    }));
    return toTreeData(categoryTreeData);
  }, [categoryTreeData]);

  const handleToggleGroupByCategory = (checked: boolean) => {
    setGroupByCategory(checked);
    setCategoryGroupingEnabled(workspaceId, currentUserId, checked);
    setSelectedRowKeys([]);
  };

  const openCategoryManage = () => {
    runWithAccess('ADMIN', i18n.t('skill:category.manage'), () => {
      startCreateCategory(null);
      setCategoryManageOpen(true);
    });
  };

  const renderCategoryGroup = (group: CategorySkillGroup): ReactNode => (
    <Collapse
      key={`category-${group.category.id}`}
      defaultActiveKey={[`category-${group.category.id}`]}
      items={[{
        key: `category-${group.category.id}`,
        label: (
          <Space size={8}>
            <Typography.Text strong>{group.category.name}</Typography.Text>
            <Typography.Text type="secondary">{t('items', { count: group.totalCount })}</Typography.Text>
          </Space>
        ),
        children: (
          <>
            {group.direct.length > 0 && (
              <Table
                rowKey="id"
                columns={columns}
                dataSource={group.direct}
                pagination={false}
                size="small"
                scroll={{ x: 1520 }}
                style={{ marginBottom: 8 }}
              />
            )}
            {group.children.length > 0 && (
              <Space direction="vertical" size={8} style={{ width: '100%', marginLeft: 12 }}>
                {group.children.map(renderCategoryGroup)}
              </Space>
            )}
          </>
        ),
      }]}
    />
  );

  const renderGroupedView = (): ReactNode => {
    // 首次加载时由外层 Spin 呈现加载态，避免闪现「暂无能力」空态
    if (groupedSkillsLoading && groupedSkills.length === 0) return null;
    if (categoryGrouping.groups.length === 0 && categoryGrouping.uncategorized.length === 0) {
      return <Empty description={i18n.t('skill:empty')} />;
    }
    return (
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {categoryGrouping.groups.map(renderCategoryGroup)}
        {categoryGrouping.uncategorized.length > 0 && (
          <Collapse
            defaultActiveKey={['uncategorized']}
            items={[{
              key: 'uncategorized',
              label: (
                <Space size={8}>
                  <Typography.Text strong>{i18n.t('skill:uncategorized')}</Typography.Text>
                  <Typography.Text type="secondary">{t('items', { count: categoryGrouping.uncategorized.length })}</Typography.Text>
                </Space>
              ),
              children: (
                <Table
                  rowKey="id"
                  columns={columns}
                  dataSource={categoryGrouping.uncategorized}
                  pagination={false}
                  size="small"
                  scroll={{ x: 1520 }}
                />
              ),
            }]}
          />
        )}
        <Typography.Text type="secondary">
          {t('grouped', { count: groupedSkills.length })}
        </Typography.Text>
      </Space>
    );
  };

  const closeDetail = () => {
    setDetailSkill(null);
    setPackageFiles([]);
    setPackageFilesError(null);
    setSelectedPackagePath(null);
    setPackageFileContent(null);
  };

  // 打开详情时才拉目录树。cancelled 标志防止快速切换技能时旧响应覆盖新技能的包内容。
  useEffect(() => {
    // 同时作废上一个技能仍在途的单文件内容请求，否则它返回后会写进新技能的预览面板
    packageFileRequestSeq.current += 1;
    setPackageFiles([]);
    setPackageFilesError(null);
    setSelectedPackagePath(null);
    setPackageFileContent(null);
    setPackageFileLoading(false);
    if (!isPackageSkill(detailSkill)) {
      setPackageFilesLoading(false);
      return;
    }
    let cancelled = false;
    setPackageFilesLoading(true);
    getSkillPackageFiles(detailSkill.id)
      .then((result) => {
        if (!cancelled) setPackageFiles(result.files ?? []);
      })
      .catch((error) => {
        if (!cancelled) setPackageFilesError(error instanceof Error && error.message ? { message: error.message } : {});
      })
      .finally(() => {
        if (!cancelled) setPackageFilesLoading(false);
      });
    return () => { cancelled = true; };
  }, [detailSkill]);

  const packageEntryMap = useMemo(
    () => new Map(packageFiles.map((file) => [file.path, file])),
    [packageFiles],
  );
  const packageTree = useMemo(() => buildPackageTree(packageFiles), [packageFiles, t]);
  const selectedPackageEntry = selectedPackagePath ? packageEntryMap.get(selectedPackagePath) ?? null : null;

  const handlePackageSelect = async (keys: Key[]) => {
    const path = keys.length > 0 ? String(keys[0]) : null;
    // 取号即作废此前所有在途请求：先发出但后返回的响应不得覆盖当前选中项
    const seq = ++packageFileRequestSeq.current;
    const stale = () => seq !== packageFileRequestSeq.current;
    setSelectedPackagePath(path);
    setPackageFileContent(null);
    setPackageFileLoading(false);
    if (!path || !detailSkill) return;
    const entry = packageEntryMap.get(path);
    // 目录只展开层级；图片与二进制只展示元信息，不请求内容
    if (!entry || entry.dir || entry.kind !== 'TEXT') return;
    setPackageFileLoading(true);
    try {
      const content = await getSkillPackageFile(detailSkill.id, path);
      if (!stale()) setPackageFileContent(content);
    } catch (error) {
      if (!stale()) message.error(errorMessage(error));
    } finally {
      if (!stale()) setPackageFileLoading(false);
    }
  };

  const handleDownloadPackage = async () => {
    if (!detailSkill) return;
    setDownloadingPackage(true);
    try {
      await downloadSkillPackage(detailSkill.id, detailSkill.packageFileName);
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setDownloadingPackage(false);
    }
  };

  const renderPackagePreview = () => {
    if (!selectedPackagePath || !selectedPackageEntry) {
      return <Typography.Text type="secondary">{i18n.t('skill:package.selectPreview')}</Typography.Text>;
    }
    if (selectedPackageEntry.dir) {
      const prefix = `${selectedPackagePath}/`;
      const childCount = packageFiles.filter((file) => file.path.startsWith(prefix)
        && !file.path.substring(prefix.length).includes('/')).length;
      return (
        <Space direction="vertical" size={4}>
          <Typography.Text strong>{selectedPackagePath}</Typography.Text>
          <Typography.Text type="secondary">{t('package.directoryCount', { count: childCount })}</Typography.Text>
        </Space>
      );
    }
    if (selectedPackageEntry.kind !== 'TEXT') {
      return (
        <Space direction="vertical" size={4}>
          <Typography.Text strong>{selectedPackageEntry.name}</Typography.Text>
          <Typography.Text type="secondary">
            {t('package.noPreview', { kind: selectedPackageEntry.kind === 'IMAGE' ? t('package.image') : t('package.binary'), size: formatBytes(selectedPackageEntry.size) })}
          </Typography.Text>
        </Space>
      );
    }
    if (packageFileLoading) {
      return <Spin />;
    }
    if (!packageFileContent) {
      return <Typography.Text type="secondary">{i18n.t('skill:package.loadFailed')}</Typography.Text>;
    }
    return isMarkdown(packageFileContent.path) ? (
      <MarkdownView content={packageFileContent.content} />
    ) : (
      // pre 的 UA 默认 white-space:pre 会关闭折行，超长行会撑破弹窗，这里强制折行
      <pre style={{
        margin: 0,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
        fontSize: 12,
      }}>
        {packageFileContent.content}
      </pre>
    );
  };

  // 成功后的收尾（关表单、提示、刷新）统一放在 handleSubmit：保存能力后还要链式设置分类，
  // 若在各 mutation 的 onSuccess 里各自处理，无法保证两条请求的先后与失败提示；
  // 列表刷新必须无条件执行——分类未变时 applyCategoryTag 不会刷新，再次编辑会读到旧行。
  const createMut = useMutation({ mutationFn: createSkill });

  const updateMut = useMutation({
    mutationFn: ({ id, data: d }: { id: number; data: Parameters<typeof updateSkill>[1] }) => updateSkill(id, d),
  });

  const createPackageMut = useMutation({
    mutationFn: ({ file, metadata }: { file: File; metadata: Parameters<typeof createSkillFromPackage>[1] }) => createSkillFromPackage(file, metadata),
  });

  const updatePackageMut = useMutation({
    mutationFn: ({ id, file, metadata }: { id: number; file: File; metadata: Parameters<typeof updateSkillPackage>[2] }) => updateSkillPackage(id, file, metadata),
  });

  const createCategoryMut = useMutation({
    mutationFn: createCategory,
    onError: (error) => message.error(errorMessage(error)),
  });

  const updateCategoryMut = useMutation({
    mutationFn: ({ id, data }: {
      id: number;
      data: Parameters<typeof updateCategory>[1];
    }) => updateCategory(id, data),
    onError: (error) => message.error(errorMessage(error)),
  });

  const deleteCategoryMut = useMutation({
    mutationFn: deleteCategory,
    onSuccess: () => {
      // 分类增删改会改变能力列表里的分类路径回显，技能查询要一并失效
      invalidateCategories();
      invalidate();
      message.success(i18n.t('skill:category.deleted'));
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const deleteMut = useMutation({
    mutationFn: deleteSkill,
    onSuccess: () => {
      invalidate();
      setPendingDeleteId(null);
      message.success(i18n.t('skill:deleted'));
    },
  });

  const testConnectionMut = useMutation({
    mutationFn: ({ skillId, executorId }: { skillId: number; executorId?: number }) => testSkillConnection(skillId, executorId),
    onSuccess: (result, variables) => {
      setConnectionResults((prev) => ({ ...prev, [variables.skillId]: result }));
      if (result.success) {
        message.success(formatConnectionResult(result));
      } else {
        message.error(formatConnectionResult(result));
      }
    },
    onError: (error, variables) => {
      const result: SkillConnectionTestResult = error instanceof Error && error.message
        ? { success: false, message: error.message }
        : { success: false, message: '', ownedFailureCode: 'CONNECTION_FAILED' };
      setConnectionResults((prev) => ({ ...prev, [variables.skillId]: result }));
      message.error(formatConnectionResult(result));
    },
    onSettled: () => setTestingSkillId(null),
  });

  const openCreate = () => {
    runWithAccess('READ_WRITE', i18n.t('skill:create'), () => {
      setEditingSkill(null);
      setAccessMode('manual');
      packageSelectionSeq.current += 1;
      setPackageReading(false);
      setZipFile(null);
      form.resetFields();
      form.setFieldsValue({ type: 'SKILL' });
      setFormOpen(true);
    });
  };

  const openEdit = (skill: Skill) => {
    runWithAccess('READ_WRITE', i18n.t('skill:edit'), () => {
      setEditingSkill(skill);
      setAccessMode(skill.sourceType === 'OSS_ZIP' ? 'package' : 'manual');
      packageSelectionSeq.current += 1;
      setPackageReading(false);
      setZipFile(null);
      let mcpConfig: Record<string, unknown> = {};
      if (skill.type === 'MCP') {
        try {
          mcpConfig = JSON.parse(skill.installSpec || '{}') as Record<string, unknown>;
        } catch {
          mcpConfig = {};
        }
      }
      form.setFieldsValue({
        type: skill.type,
        name: skill.name,
        installSpec: skill.installSpec,
        description: skill.description,
        categoryId: skill.categoryId == null ? UNCATEGORIZED : String(skill.categoryId),
        mcpTransport: mcpConfig.transport || 'http',
        mcpUrl: mcpConfig.url,
        mcpCommand: mcpConfig.command,
        mcpArgs: Array.isArray(mcpConfig.args)
          ? mcpConfig.args.map((value) => ({ value: String(value) }))
          : [],
        mcpHeaders: mcpValues(mcpConfig.headers),
        mcpEnv: mcpValues(mcpConfig.env),
        mcpTimeoutSeconds: mcpConfig.timeoutSeconds || 60,
        providers: skill.type === 'PLUGIN' ? pluginProviders(skill.installSpec) : undefined,
      });
      setFormOpen(true);
    });
  };

  // 保存能力后链式设置分类：值没变就不请求；新建且选「未分类」也跳过（默认即未分类）。
  // 打标失败不影响已保存的能力内容，但要明确提示。
  const applyCategoryTag = async (skillId: number, categoryValue: unknown) => {
    const target = categoryValue == null || categoryValue === UNCATEGORIZED ? null : Number(categoryValue);
    const before = editingSkill?.categoryId ?? null;
    if (!editingSkill && target === null) return;
    if (editingSkill && target === before) return;
    try {
      await setSkillCategory(skillId, target);
      invalidate();
    } catch (error) {
      message.error(i18n.t('skill:category.savedTagFailed', { reason: errorMessage(error) }));
    }
  };

  const handleSubmit = async () => {
    await runWithAccess('READ_WRITE', editingSkill ? i18n.t('skill:edit') : i18n.t('skill:create'), async () => {
      if (accessMode === 'package') {
        if (packageReading || !zipFile) {
          message.error(i18n.t('skill:package.selectFirst'));
          return;
        }
        const values = await form.validateFields().catch(() => null);
        if (!values) return;
        const metadata = { type: values.type, name: values.name, description: values.description, providers: values.providers };
        try {
          const skill = editingSkill
            ? await updatePackageMut.mutateAsync({ id: editingSkill.id, file: zipFile, metadata })
            : await createPackageMut.mutateAsync({ file: zipFile, metadata });
          await invalidate();
          await applyCategoryTag(skill.id, values.categoryId);
          message.success(editingSkill ? i18n.t('skill:package.replaced') : i18n.t('skill:package.uploaded'));
          closeForm();
        } catch (error) {
          message.error(errorMessage(error));
        }
        return;
      }
      const values = await form.validateFields().catch(() => null);
      if (!values) return;
      if (values.type === 'MCP') {
        const headers = serializeMcpValues(values.mcpHeaders);
        const env = serializeMcpValues(values.mcpEnv);
        values.installSpec = JSON.stringify(values.mcpTransport === 'stdio'
          ? {
            transport: 'stdio',
            command: values.mcpCommand,
            args: (values.mcpArgs || []).map((item: { value?: string }) => item.value?.trim()).filter(Boolean),
            env,
          }
          : {
            transport: values.mcpTransport || 'http',
            url: values.mcpUrl,
            headers,
            timeoutSeconds: values.mcpTimeoutSeconds || 60,
          });
      }
      try {
        const skill = editingSkill
          ? await updateMut.mutateAsync({
            id: editingSkill.id,
            data: {
              name: values.name,
              installSpec: values.installSpec,
              description: values.description,
            },
          })
          : await createMut.mutateAsync(values);
        await invalidate();
        await applyCategoryTag(skill.id, values.categoryId);
        message.success(editingSkill ? i18n.t('skill:saved') : i18n.t('skill:created'));
        closeForm();
      } catch (error) {
        message.error(errorMessage(error));
      }
    });
  };

  const closeForm = () => {
    setFormOpen(false);
    setEditingSkill(null);
    packageSelectionSeq.current += 1;
    setPackageReading(false);
    setZipFile(null);
    form.resetFields();
  };

  const handlePackageSelectFiles = async (files: FileList | null, directory: boolean) => {
    if (!files?.length) return;
    const seq = ++packageSelectionSeq.current;
    setZipFile(null);
    setPackageReading(true);
    if (selectedType === 'SKILL') form.setFieldsValue({ name: '', description: '' });
    try {
      const file = directory ? await buildDirectoryZip(files) : files[0];
      if (!file.name.toLowerCase().endsWith('.zip')) throw new Error(i18n.t('skill:package.zipRequired'));
      if (file.size > MAX_SKILL_PACKAGE_BYTES) throw new Error(i18n.t('skill:package.archiveLimit'));
      const metadata = selectedType === 'SKILL' ? await inspectSkillPackage(file) : null;
      if (seq !== packageSelectionSeq.current) return;
      setZipFile(file);
      if (metadata) form.setFieldsValue({ name: metadata.name, description: metadata.description });
    } catch (e) {
      if (seq === packageSelectionSeq.current) message.error(errorMessage(e));
    } finally {
      if (seq === packageSelectionSeq.current) setPackageReading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
      if (zipInputRef.current) zipInputRef.current.value = '';
    }
  };

  const handleTestConnection = (skill: Skill) => {
    runWithAccess('READ_WRITE', i18n.t('skill:connection.testAction'), () => {
      setTestTargetSkill(skill);
      setTestExecutorId(executors.find((executor) => executor.status === 'ONLINE')?.id);
    });
  };

  const startConnectionTest = (skill: Skill, executorId?: number) => {
    setTestingSkillId(skill.id);
    setConnectionResults((prev) => {
      const next = { ...prev };
      delete next[skill.id];
      return next;
    });
    testConnectionMut.mutate({ skillId: skill.id, executorId });
  };

  const handleCategorySubmit = async () => {
    await runWithAccess('ADMIN', editingCategoryId ? i18n.t('skill:category.update') : i18n.t('skill:category.create'), async () => {
      // 校验失败由 antd 在字段上内联提示，静默返回即可，不能让拒绝逃逸成 unhandled rejection
      const values = await categoryForm.validateFields().catch(() => null);
      if (!values) return;
      const data = {
        name: values.name as string,
        parentId: values.parentId === UNCATEGORIZED || values.parentId == null
          ? null
          : Number(values.parentId),
        description: ((values.description as string | undefined) || '').trim() || null,
      };
      try {
        const saved = editingCategoryId
          ? await updateCategoryMut.mutateAsync({ id: editingCategoryId, data })
          : await createCategoryMut.mutateAsync(data);
        // 分类变更会让能力列表的分类路径回显失效
        invalidateCategories();
        invalidate();
        message.success(editingCategoryId ? i18n.t('skill:category.saved') : i18n.t('skill:category.created'));
        // 保存后停在编辑态，直接用响应回填表单（列表刷新前 categories 里可能还没有新节点）
        setEditingCategoryId(saved.id);
        categoryForm.setFieldsValue({
          name: saved.name,
          parentId: saved.parentId == null ? UNCATEGORIZED : String(saved.parentId),
          description: saved.description || '',
        });
      } catch {
        // onError 已提示
      }
    });
  };

  const handleDeleteCategory = async (id: number) => {
    try {
      await deleteCategoryMut.mutateAsync(id);
      if (editingCategoryId === id) {
        startCreateCategory(null);
      }
    } catch {
      // onError 已提示（非空删除等服务端拒绝场景）
    }
  };

  const handleBatchApply = async () => {
    if (selectedRowKeys.length === 0) return;
    await runWithAccess('READ_WRITE', i18n.t('skill:category.batchAction'), async () => {
      const categoryId = batchCategoryValue === UNCATEGORIZED ? null : Number(batchCategoryValue);
      setBatchApplying(true);
      try {
        const results: BatchSkillCategoryResult[] = await batchSetSkillCategory(
          selectedRowKeys.map(Number),
          categoryId,
        );
        const failed = results.filter((item) => !item.success);
        if (failed.length === 0) {
          message.success(i18n.t('skill:category.batchSaved', { count: results.length }));
        } else {
          message.warning(
            i18n.t('skill:category.batchFailed', { succeeded: formatNumber(results.length - failed.length), failed: formatNumber(failed.length), reasons: failed.map((item) => `#${item.skillId} ${item.message ?? ''}`.trim()).join(i18n.t('skill:category.failureSeparator')) }),
          );
        }
        setBatchCategoryOpen(false);
        setSelectedRowKeys([]);
        invalidate();
      } catch (error) {
        message.error(errorMessage(error));
      } finally {
        setBatchApplying(false);
      }
    });
  };

  const columns: ColumnsType<Skill> = [
    { title: t('id'), dataIndex: 'id', width: 70 },
    {
      title: t('name'), dataIndex: 'name', align: 'left',
      render: (v: string) => <EllipsisText tooltip={v}>{v}</EllipsisText>,
    },
    {
      // 描述内容普遍很长，悬停提示意义不大：纯省略，不加 tooltip
      title: t('description'), dataIndex: 'description', align: 'left', ellipsis: true,
      render: (v: string) => v ?? '—',
    },
    {
      title: t('type'), dataIndex: 'type', width: 90,
      render: (type: Skill['type']) => <Tag color={Object.prototype.hasOwnProperty.call(typeColor, type) ? typeColor[type] : undefined}>{skillTypeLabel(type)}</Tag>,
    },
    {
      title: t('category.label'), key: 'category', width: 160,
      render: (_, record) => activeCategorySkillId === record.id ? (
        <TreeSelect
          autoFocus
          open
          onDropdownVisibleChange={(open) => { if (!open) setActiveCategorySkillId(null); }}
          onBlur={() => setActiveCategorySkillId(null)}
          onKeyDown={(event) => { if (event.key === 'Escape') setActiveCategorySkillId(null); }}
          aria-label={t('category.rowLabel', { name: record.name })}
          style={{ width: 130 }}
          dropdownMatchSelectWidth={260}
          showSearch
          treeDefaultExpandAll
          treeLine
          treeNodeFilterProp="path"
          treeNodeLabelProp="path"
          placeholder={t('category.select')}
          allowClear={{ clearIcon: <CloseCircleFilled role="button" aria-label={t('clearCategory')} /> }}
          value={record.categoryId == null ? undefined : String(record.categoryId)}
          treeData={categorySelectTree}
          loading={savingCategoryIds.includes(record.id)}
          disabled={savingCategoryIds.includes(record.id)}
          onChange={(value) => runWithAccess('READ_WRITE', t('category.set'), async () => {
            setActiveCategorySkillId(null);
            setSavingCategoryIds((ids) => [...ids, record.id]);
            try {
              await setSkillCategory(record.id, value == null ? null : Number(value));
              await invalidate();
              message.success(i18n.t('skill:category.saved'));
            } catch (error) {
              message.error(errorMessage(error));
            } finally {
              setSavingCategoryIds((ids) => ids.filter((id) => id !== record.id));
            }
          })}
        />
      ) : (
        <button
          type="button"
          aria-label={t('category.rowEdit', { name: record.name })}
          title={record.categoryPath || t('category.select')}
          disabled={savingCategoryIds.includes(record.id)}
          onClick={() => runWithAccess('READ_WRITE', t('category.set'), () => setActiveCategorySkillId(record.id))}
          style={{ border: 0, background: 'none', padding: 0, font: 'inherit',
            color: record.categoryId == null ? 'var(--aw-muted)' : 'inherit', cursor: 'pointer',
            maxWidth: 130, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', textAlign: 'left' }}
        >
          {savingCategoryIds.includes(record.id) ? t('saving') : (record.categoryPath || '—')}
        </button>
      ),
    },
    {
      title: t('actions'), width: 280, fixed: 'right',
      render: (_, record) => {
        const connectionResult = connectionResults[record.id];
        return (
          <Space size={4} wrap>
            <Button type="link" size="small" icon={<FileTextOutlined aria-hidden="true" />} onClick={() => setDetailSkill(record)}>{t('detail')}</Button>
            {record.type === 'MCP' && (
              <Button
                type="link"
                size="small"
                loading={testingSkillId === record.id}
                onClick={() => handleTestConnection(record)}
              >
                {t('connection.test')}</Button>
            )}
            {record.type === 'MCP' && connectionResult && (
              <Tag color={connectionResult.success ? 'success' : 'error'}>
                {formatConnectionResult(connectionResult)}
              </Tag>
            )}
            {record.type === 'MCP' && connectionResult?.success && connectionResult.tools && (
              <Button type="link" size="small" onClick={() => setToolListResult(connectionResult)}>
                {t('connection.viewTools', { count: connectionResult.tools.length })}
              </Button>
            )}
            <Button type="link" size="small" icon={<EditOutlined aria-hidden="true" />} onClick={() => openEdit(record)}>{t('editButton')}</Button>
            <Popconfirm
              title={t('deleteConfirm')}
              open={pendingDeleteId === record.id}
              onOpenChange={(open) => {
                if (!open) setPendingDeleteId(null);
              }}
              onConfirm={() => runWithAccess(
                'READ_WRITE',
                t('deleteAction'),
                () => deleteMut.mutate(record.id),
              )}
            >
              <Button
                type="link"
                size="small"
                danger
                icon={<DeleteOutlined aria-hidden="true" />}
                onClick={() => runWithAccess(
                  'READ_WRITE',
                  t('deleteAction'),
                  () => setPendingDeleteId(record.id),
                )}
              >
                {t('delete')}</Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <>
      <Card
        className="aw-content-card" title={<PageHeading title={t('title')} />}
        extra={<Button type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={openCreate}>{t('create')}</Button>}
      >
        <div style={{
          marginBottom: 16,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 12,
          flexWrap: 'wrap',
        }}>
          <Segmented
            options={filterTypeOptions}
            value={typeFilter}
            onChange={(v) => { setTypeFilter(v as string); setPage(1); }}
          />
          <Space size={16}>
            <Space size={8}>
              <Typography.Text type="secondary">{t('category.group')}</Typography.Text>
              <Switch
                checked={groupByCategory}
                onChange={handleToggleGroupByCategory}
                aria-label={t('category.group')}
              />
            </Space>
            <Button icon={<ApartmentOutlined aria-hidden="true" />} onClick={openCategoryManage}>{t('category.manage')}</Button>
          </Space>
        </div>
        {groupByCategory ? (
          <Spin spinning={groupedSkillsLoading}>
            {renderGroupedView()}
          </Spin>
        ) : (
          <>
            {selectedRowKeys.length > 0 && (
              <div style={{ marginBottom: 12 }}>
                <Space>
                  <Typography.Text type="secondary">{t('selected', { count: selectedRowKeys.length })}</Typography.Text>
                  <Button
                    size="small"
                    onClick={() => runWithAccess('READ_WRITE', t('category.batchAction'), () => setBatchCategoryOpen(true))}
                  >
                    {t('category.batch')}</Button>
                  <Button size="small" type="text" onClick={() => setSelectedRowKeys([])}>{t('clearSelection')}</Button>
                </Space>
              </div>
            )}
            <Table
              rowKey="id"
              columns={columns}
              dataSource={skills}
              loading={isLoading}
              rowSelection={{
                getCheckboxProps: (record): CheckboxProps & Pick<AriaAttributes, 'aria-label'> => ({ 'aria-label': t('selection.row', { name: record.name }) }),
                getTitleCheckboxProps: () => ({ 'aria-label': t('selection.all') }),
                selectedRowKeys,
                onChange: (keys) => setSelectedRowKeys(keys),
              }}
              pagination={{
                current: page, pageSize: size, total,
                onChange: (p, ps) => { setPage(p); setSize(ps); },
                showSizeChanger: true,
                showTotal: (count) => t('count', { count }),
              }}
              scroll={{ x: 900 }}
            />
          </>
        )}
      </Card>

      <Modal
        closable={{ 'aria-label': t('close') }}
        title={t('connection.runtimeTitle')}
        open={!!testTargetSkill}
        onCancel={() => setTestTargetSkill(null)}
        onOk={() => {
          if (!testTargetSkill || !testExecutorId) return;
          const skill = testTargetSkill;
          setTestTargetSkill(null);
          startConnectionTest(skill, testExecutorId);
        }}
        okButtonProps={{ disabled: !testExecutorId }}
      >
        <Alert type="info" showIcon message={t('connection.runtimeHint')} style={{ marginBottom: 16 }} />
        <Select
          style={{ width: '100%' }}
          value={testExecutorId}
          onChange={setTestExecutorId}
          placeholder={t('connection.runtimeSelect')}
          options={executors.filter((executor) => executor.status === 'ONLINE').map((executor) => ({
            value: executor.id,
            label: t('connection.runtimeOption', { name: executor.name, agent: executor.agentName || t('connection.unnamedAgent'), id: executor.id }),
          }))}
          notFoundContent={t('connection.noRuntime')}
        />
      </Modal>
      <Modal
        closable={{ 'aria-label': t('close') }}
        title={t('detailTitle')}
        open={!!detailSkill}
        onCancel={closeDetail}
        footer={<Button onClick={closeDetail}>{t('close')}</Button>}
        width={720}
      >
        {detailSkill && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label={t('name')} span={2}>{detailSkill.name}</Descriptions.Item>
              <Descriptions.Item label={t('type')}>
                <Tag color={Object.prototype.hasOwnProperty.call(typeColor, detailSkill.type) ? typeColor[detailSkill.type] : undefined}>{skillTypeLabel(detailSkill.type)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('access.label')}>{accessLabel(detailSkill)}</Descriptions.Item>
              <Descriptions.Item label={t('category.label')}>{detailSkill.categoryPath || t('uncategorized')}</Descriptions.Item>
              <Descriptions.Item label={t('version')}>{detailSkill.version}</Descriptions.Item>
              <Descriptions.Item label={t('modifier')}>
                {modifierName(detailSkill)}
              </Descriptions.Item>
              <Descriptions.Item label={t('modifiedAt')} span={2}>{formatDateTime(detailSkill.gmtModified)}</Descriptions.Item>
            </Descriptions>
            <div>
              <Typography.Text strong>{t('description')}</Typography.Text>
              <div style={{
                marginTop: 8,
                padding: 12,
                background: 'var(--aw-raised)',
                border: '1px solid var(--aw-border)',
                borderRadius: 8,
                whiteSpace: 'pre-wrap',
              }}>
                {detailSkill.description || t('noDescription')}
              </div>
            </div>
            <div>
              <Typography.Text strong>{detailSkill.sourceType === 'OSS_ZIP' ? t('package.info') : t('access.installCommand')}</Typography.Text>
              <pre style={{
                marginTop: 8,
                padding: 12,
                background: 'var(--aw-raised)',
                color: 'var(--aw-text)',
                borderRadius: 8,
                overflowX: 'auto',
                whiteSpace: 'pre-wrap',
              }}>
                {detailAccessText(detailSkill)}
              </pre>
            </div>
            {detailSkill.packageOssRef && (
              <>
                <Divider style={{ margin: 0 }} />
                <Typography.Text type="secondary">
                  {t('package.ossRef', { ref: detailSkill.packageOssRef })}
                </Typography.Text>
              </>
            )}
            {isPackageSkill(detailSkill) && (
              <>
                <Divider style={{ margin: 0 }} />
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <Typography.Text strong>{t('package.contents')}</Typography.Text>
                    <Button
                      size="small"
                      icon={<DownloadOutlined aria-hidden="true" />}
                      loading={downloadingPackage}
                      onClick={handleDownloadPackage}
                    >
                      {t('package.download')}</Button>
                  </div>
                  {packageFilesError && (
                    <Alert type="error" showIcon style={{ marginTop: 8 }} message={packageFilesError.message || t('errors.operation')} />
                  )}
                  <Spin spinning={packageFilesLoading}>
                    <div style={{ display: 'flex', gap: 12, marginTop: 8 }}>
                      <div
                        data-testid="skill-package-tree"
                        style={{
                          width: 260,
                          flexShrink: 0,
                          maxHeight: 300,
                          overflow: 'auto',
                          border: '1px solid var(--aw-border)',
                          borderRadius: 8,
                          padding: 8,
                        }}
                      >
                        {packageTree.length > 0 ? (
                          <Tree
                            treeData={packageTree}
                            selectedKeys={selectedPackagePath ? [selectedPackagePath] : []}
                            onSelect={handlePackageSelect}
                            showLine
                            blockNode
                            defaultExpandAll
                          />
                        ) : packageFilesLoading || packageFilesError ? null : (
                          // 清单拉取失败时上方已有错误 Alert，再显示空态会被误读为包本身没内容
                          <Typography.Text type="secondary">{t('package.empty')}</Typography.Text>
                        )}
                      </div>
                      <div
                        data-testid="skill-package-preview"
                        style={{
                          flex: 1,
                          minWidth: 0,
                          maxHeight: 300,
                          overflow: 'auto',
                          border: '1px solid var(--aw-border)',
                          borderRadius: 8,
                          padding: 12,
                        }}
                      >
                        {renderPackagePreview()}
                      </div>
                    </div>
                  </Spin>
                </div>
              </>
            )}
          </Space>
        )}
      </Modal>

      <Modal
        closable={{ 'aria-label': t('close') }}
        title={editingSkill ? t('edit') : t('create')}
        open={formOpen}
        width={680}
        forceRender
        onOk={handleSubmit}
        onCancel={closeForm}
        confirmLoading={packageReading || createMut.isPending || updateMut.isPending || createPackageMut.isPending || updatePackageMut.isPending}
      >
        <Form name="skillEditor" form={form} layout="vertical">
          <Form.Item name="type" label={t('type')} rules={[{ required: true }]}>
			<Select disabled={!!editingSkill} onChange={(value) => {
              packageSelectionSeq.current += 1;
              setPackageReading(false);
              setZipFile(null);
              setAccessMode(value === 'PLUGIN' || value === 'HOOK' ? 'package' : 'manual');
            }}
              options={creatableTypeOptions} />
          </Form.Item>
          {(selectedType === 'SKILL' || selectedType === 'PLUGIN' || selectedType === 'HOOK') && (
            <Form.Item label={t('access.label')}>
              <Radio.Group
                value={accessMode}
                onChange={(e) => {
                  setAccessMode(e.target.value);
                  packageSelectionSeq.current += 1;
                  setPackageReading(false);
                  setZipFile(null);
                }}
                options={selectedType === 'PLUGIN' || selectedType === 'HOOK'
                  ? [{ value: 'package', label: selectedType === 'HOOK' ? t('package.uploadHook') : t('package.uploadPlugin') }]
                  : [{ value: 'manual', label: t('access.manual') }, { value: 'package', label: t('package.uploadFolder') }]}
              />
            </Form.Item>
          )}
          {accessMode === 'package' && selectedType !== 'MCP' && (
            <Form.Item label={t('package.label')} required>
              <Space direction="vertical" style={{ width: '100%' }}>
                <Alert type="info" showIcon message={t('package.limitsTitle')} description={t('package.limit')} />
                <Space>
                  <Button icon={<FolderOpenOutlined aria-hidden="true" />} onClick={() => fileInputRef.current?.click()}>
                    {t('package.chooseFolder')}</Button>
                  <Button icon={<FileTextOutlined aria-hidden="true" />} onClick={() => zipInputRef.current?.click()}>
                    {t('package.chooseZip')}</Button>
                </Space>
                <input
                  ref={(node) => {
                    fileInputRef.current = node;
                    node?.setAttribute('webkitdirectory', '');
                    node?.setAttribute('directory', '');
                  }}
                  aria-label={t('package.folderInput')}
                  type="file"
                  multiple
                  style={{ display: 'none' }}
                  onChange={(e) => handlePackageSelectFiles(e.target.files, true)}
                />
                <input
                  ref={zipInputRef}
                  aria-label={t('package.zipInput')}
                  type="file"
                  accept=".zip,application/zip"
                  style={{ display: 'none' }}
                  onChange={(e) => handlePackageSelectFiles(e.target.files, false)}
                />
                {packageReading ? <Spin /> : zipFile ? (
                  <Alert type="success" showIcon message={t('package.selected', { name: zipFile.name })} description={formatBytes(zipFile.size)} />
                ) : (
                  <Alert type="info" showIcon
                    message={selectedType === 'SKILL' ? t('package.skillRequired')
                      : selectedType === 'HOOK' ? t('package.hookRequired') : t('package.pluginRequired')}
                    description={editingSkill?.sourceType === 'OSS_ZIP'
                      ? t('package.current', { name: editingSkill.packageFileName || editingSkill.packageOssRef || t('package.alreadyUploaded') })
                      : selectedType === 'SKILL' ? t('package.frontmatter') : undefined}
                  />
                )}
              </Space>
            </Form.Item>
          )}
          {selectedType === 'PLUGIN' && (
            <Form.Item name="providers" label={t('providers')} rules={[{ required: true, message: t('validation.provider') }]}>
              <Select mode="multiple" options={[{ value: 'claude' }, { value: 'qoder' }]} />
            </Form.Item>
          )}
          <Form.Item name="name" label={t('name')}
            rules={selectedType === 'HOOK' ? [] : [{ required: true, message: t('validation.name') }]}>
            <Input disabled={accessMode === 'package' && selectedType === 'SKILL'} placeholder={t('placeholder.name')} />
          </Form.Item>
          <Form.Item
            name="categoryId"
            label={t('category.optional')}
            initialValue={UNCATEGORIZED}
            extra={t('category.tagHint')}
          >
            <Select options={categorySelectOptions} placeholder={t('uncategorized')} />
          </Form.Item>
          {selectedType === 'MCP' && (
            <>
              <Form.Item name="mcpTransport" label={t('mcp.serverType')} initialValue="http">
                <Select options={[
                  { value: 'http', label: t('mcp.http') },
                  { value: 'sse', label: t('mcp.sse') },
                  { value: 'stdio', label: t('mcp.stdio') },
                ]} />
              </Form.Item>
              <Form.Item noStyle shouldUpdate={(prev, next) => prev.mcpTransport !== next.mcpTransport}>
                {({ getFieldValue }) => getFieldValue('mcpTransport') === 'stdio' ? (
                  <>
                    <Form.Item name="mcpCommand" label={t('mcp.executable')} rules={[{ required: true }]}><Input placeholder={t('placeholder.command')} /></Form.Item>
                    <Form.List name="mcpArgs">
                      {(fields, { add, remove }) => <Form.Item label={t('mcp.args')}>
                        {fields.map((field) => <div key={field.key} style={{ display: 'flex', width: '100%', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                          <Form.Item {...field} name={[field.name, 'value']} rules={[{ required: true, message: t('validation.argument') }]} style={{ flex: 1, minWidth: 0, marginBottom: 0 }}>
                            <Input aria-label={t('mcp.argument')} placeholder={t('placeholder.argument')} />
                          </Form.Item>
                          <Button type="text" aria-label={t('mcp.removeArgument')} onClick={() => remove(field.name)} icon={<MinusCircleOutlined aria-hidden="true" />} />
                        </div>)}
                        <Button block type="dashed" onClick={() => add()} icon={<PlusOutlined aria-hidden="true" />}>{t('mcp.addArgument')}</Button>
                      </Form.Item>}
                    </Form.List>
                    <Form.List name="mcpEnv">
                      {(fields, { add, remove }) => <Form.Item label={t('mcp.env')}>
                        {fields.map((field) => <div key={field.key} style={{ display: 'flex', width: '100%', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                          <Form.Item {...field} name={[field.name, 'name']} rules={[{ required: true, message: t('validation.envName') }]} style={{ flex: '0 1 240px', minWidth: 0, marginBottom: 0 }}>
                            <Input aria-label={t('placeholder.envName')} placeholder={t('placeholder.envName')} />
                          </Form.Item>
                          <Form.Item {...field} name={[field.name, 'value']} rules={[{ required: true, message: t('validation.envValue') }]} style={{ flex: 1, minWidth: 0, marginBottom: 0 }}>
                            <Input aria-label={t('placeholder.envValue')} placeholder={t('placeholder.envValue')} autoComplete="new-password" onFocus={(event) => { if (form.getFieldValue(['mcpEnv', field.name, 'secret']) && event.currentTarget.value === AUTHORIZATION_MASK) form.setFieldValue(['mcpEnv', field.name, 'value'], ''); }} />
                          </Form.Item>
                          <Form.Item {...field} name={[field.name, 'secret']} valuePropName="checked" style={{ flex: 'none', marginBottom: 0 }}>
                            <SecretCheckbox label={t('mcp.private')} hint={t('mcp.secretHint')} />
                          </Form.Item>
                          <Button type="text" aria-label={t('mcp.removeEnv')} onClick={() => remove(field.name)} icon={<MinusCircleOutlined aria-hidden="true" />} />
                        </div>)}
                        <Button block type="dashed" onClick={() => add()} icon={<PlusOutlined aria-hidden="true" />}>{t('mcp.addEnv')}</Button>
                      </Form.Item>}
                    </Form.List>
                  </>
                ) : (
                  <>
                    <Form.Item name="mcpUrl" label={t('mcp.url')} rules={[{ required: true, type: 'url' }]}><Input placeholder="https://example.com/mcp" /></Form.Item>
                    <Form.List name="mcpHeaders">
                      {(fields, { add, remove }) => (
                        <Form.Item label={t('mcp.headers')}>
                          {fields.map((field) => (
                            <div key={field.key} style={{ display: 'flex', width: '100%', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                              <Form.Item {...field} name={[field.name, 'name']} rules={[{ required: true, message: t('validation.headerName') }]} style={{ flex: '0 1 180px', minWidth: 0, marginBottom: 0 }}>
                                <Input aria-label={t('mcp.headerName')} placeholder="Authorization" />
                              </Form.Item>
                              <Form.Item {...field} name={[field.name, 'value']} rules={[{ required: true, message: t('validation.headerValue') }]} style={{ flex: 1, minWidth: 0, marginBottom: 0 }}>
                                <Input
                                  aria-label={t('mcp.headerValue')}
                                  placeholder="Bearer your-token"
                                  autoComplete="new-password"
                                  onFocus={(event) => {
                                    if (form.getFieldValue(['mcpHeaders', field.name, 'secret']) && event.currentTarget.value === AUTHORIZATION_MASK) {
                                      form.setFieldValue(['mcpHeaders', field.name, 'value'], '');
                                    }
                                  }}
                                />
                              </Form.Item>
                              <Form.Item {...field} name={[field.name, 'secret']} valuePropName="checked" style={{ flex: 'none', marginBottom: 0 }}>
                                <SecretCheckbox label={t('mcp.private')} hint={t('mcp.secretHint')} />
                              </Form.Item>
                              <Button type="text" aria-label={t('mcp.removeHeader')} onClick={() => remove(field.name)} icon={<MinusCircleOutlined aria-hidden="true" />} />
                            </div>
                          ))}
                          <Button block type="dashed" onClick={() => add()} icon={<PlusOutlined aria-hidden="true" />}>{t('mcp.addHeader')}</Button>
                        </Form.Item>
                      )}
                    </Form.List>
                    <Form.Item name="mcpTimeoutSeconds" label={t('mcp.timeout')} initialValue={60}
                      rules={[{ required: true, message: t('validation.timeout') }]} extra={t('mcp.timeoutHint')}>
                      <InputNumber min={1} max={600} precision={0} style={{ width: '100%' }} />
                    </Form.Item>
                  </>
                )}
              </Form.Item>
            </>
          )}
          {accessMode === 'manual' && selectedType === 'SKILL' && (
            <Form.Item name="installSpec" label={t('access.install')} rules={[{ required: true, message: t('validation.install') }]}
              tooltip={t('placeholder.installHint')}>
              <Input.TextArea rows={2} placeholder={t('placeholder.install')} />
            </Form.Item>
          )}
          <Form.Item name="description" label={t('description')}>
            <Input.TextArea disabled={accessMode === 'package' && selectedType === 'SKILL'} rows={2} placeholder={t('placeholder.description')} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal closable={{ 'aria-label': t('close') }} title={t('connection.tools', { count: toolListResult?.tools?.length || 0 })} open={toolListResult !== null}
        footer={null} onCancel={() => setToolListResult(null)} width={680}>
        {(toolListResult?.tools || []).length > 0 && <Collapse
          expandIconPosition="start"
          items={(toolListResult?.tools || []).map((tool, index) => ({
            key: `${tool.name || 'tool'}-${index}`,
            collapsible: 'icon',
            label: <Typography.Text strong>{tool.name || '-'}</Typography.Text>,
            children: <>
              {tool.description && <Typography.Paragraph>{tool.description}</Typography.Paragraph>}
              {tool.inputSchema !== undefined && <Typography.Paragraph code style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                {JSON.stringify(tool.inputSchema, null, 2)}
              </Typography.Paragraph>}
            </>,
          }))}
        />}
        {toolListResult?.tools?.length === 0 && <Typography.Text type="secondary">{t('connection.emptyTools')}</Typography.Text>}
      </Modal>

      <Modal
        closable={{ 'aria-label': t('close') }}
        title={t('category.manage')}
        open={categoryManageOpen}
        onCancel={() => setCategoryManageOpen(false)}
        footer={null}
        width={860}
        forceRender
      >
        <div style={{ display: 'flex', gap: 16, minHeight: 320 }}>
          <div
            data-testid="category-manage-tree"
            style={{
              width: 300,
              flexShrink: 0,
              maxHeight: 420,
              overflow: 'auto',
              border: '1px solid var(--aw-border)',
              borderRadius: 8,
              padding: 12,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
              <Typography.Text type="secondary">{t('category.directory')}</Typography.Text>
              <Button
                size="small"
                icon={<PlusOutlined aria-hidden="true" />}
                onClick={() => runWithAccess('ADMIN', t('category.create'), () => startCreateCategory(editingCategoryId))}
              >
                {t('category.add')}</Button>
            </div>
            {categories.length === 0 ? (
              <Typography.Text type="secondary">{t('category.empty')}</Typography.Text>
            ) : (
              <Tree
                treeData={manageTreeData}
                titleRender={(node) => (
                  <span style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                    <span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {node.title as ReactNode}
                    </span>
                    {editingCategoryId === node.key && (
                      <span onClick={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}>
                        <Popconfirm
                          title={t('category.deleteConfirm', { name: node.title as string })}
                          description={t('category.deleteHint')}
                          open={pendingDeleteCategoryId === node.key}
                          onOpenChange={(open) => { if (!open) setPendingDeleteCategoryId(null); }}
                          onConfirm={() => runWithAccess('ADMIN', t('category.delete'),
                            () => handleDeleteCategory(Number(node.key)))}
                          okText={t('delete')}
                          cancelText={t('cancel')}
                          okButtonProps={{ danger: true, loading: deleteCategoryMut.isPending }}
                        >
                          <Button type="text" size="small" danger icon={<DeleteOutlined aria-hidden="true" />}
                            aria-label={t('category.deleteLabel', { name: node.title as string })}
                            title={t('category.delete')}
                            loading={deleteCategoryMut.isPending}
                            onClick={() => runWithAccess('ADMIN', t('category.delete'),
                              () => setPendingDeleteCategoryId(Number(node.key)))}
                          />
                        </Popconfirm>
                      </span>
                    )}
                  </span>
                )}
                selectedKeys={editingCategoryId != null ? [editingCategoryId] : []}
                expandedKeys={categoryExpandedKeys}
                onExpand={(keys) => setCategoryExpandedKeys(keys)}
                onSelect={(keys) => {
                  // 取消选中不重置表单，避免误触丢失正在编辑的内容
                  if (keys.length === 0) return;
                  editCategory(Number(keys[0]));
                }}
                blockNode
              />
            )}
            <Divider style={{ margin: '12px 0 8px' }} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('category.systemView')}</Typography.Text>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {editingCategoryId != null
                ? t('category.current', { path: categories.find((item) => item.id === editingCategoryId)?.path ?? '' })
                : t('category.add')}
            </Typography.Text>
            <Form name="skillCategory" form={categoryForm} layout="vertical" style={{ marginTop: 8 }}>
              <Form.Item
                name="name"
                label={t('category.name')}
                rules={[
                  { required: true, message: t('validation.categoryName') },
                  { max: 50, message: t('validation.categoryNameMax') },
                ]}
              >
                <Input maxLength={50} placeholder={t('placeholder.categoryName')} showCount />
              </Form.Item>
              <Form.Item
                name="parentId"
                label={t('category.parent')}
                initialValue={UNCATEGORIZED}
                extra={t('category.parentHint')}
              >
                <TreeSelect
                  treeData={parentCategoryOptions}
                  treeDefaultExpandAll
                  treeLine
                  showSearch
                  treeNodeFilterProp="path"
                  treeNodeLabelProp="path"
                />
              </Form.Item>
              <Form.Item
                name="description"
                label={t('category.description')}
                extra={t('category.descriptionHint')}
              >
                <Input.TextArea rows={3} maxLength={1000} placeholder={t('placeholder.categoryDescription')} />
              </Form.Item>
              <Form.Item style={{ marginBottom: 0 }}>
                <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center',
                  borderTop: '1px solid var(--aw-border)', paddingTop: 16 }}>
                  <Space>
                    <Button onClick={() => setCategoryManageOpen(false)}>{t('cancel')}</Button>
                    <Button
                      type="primary"
                      loading={createCategoryMut.isPending || updateCategoryMut.isPending}
                      onClick={handleCategorySubmit}
                    >
                      {t('category.save')}</Button>
                  </Space>
                </div>
              </Form.Item>
            </Form>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('category.editHint')}</Typography.Text>
          </div>
        </div>
      </Modal>

      <Modal
        closable={{ 'aria-label': t('close') }}
        title={t('category.batch')}
        open={batchCategoryOpen}
        onCancel={() => setBatchCategoryOpen(false)}
        onOk={handleBatchApply}
        confirmLoading={batchApplying}
        okText={t('apply')}
        width={520}
      >
        <Typography.Paragraph type="secondary">
          {t('category.batchHint', { count: selectedRowKeys.length })}
        </Typography.Paragraph>
        <Form layout="vertical">
          <Form.Item label={t('category.target')}>
            <Select
              value={batchCategoryValue}
              onChange={setBatchCategoryValue}
              options={categorySelectOptions}
              aria-label={t('category.target')}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

function modifierName(skill: Skill): string {
  return skill.modifierNameKind === 'USER_ID_FALLBACK' && skill.modifierId != null
    ? i18n.t('skill:modifierFallback', { id: skill.modifierId })
    : skill.modifierName || (skill.modifierId ? i18n.t('skill:modifierFallback', { id: skill.modifierId }) : '-');
}

function formatDateTime(value?: string) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return formatSharedDateTime(value, { hour12: false });
}

function formatConnectionResult(result: SkillConnectionTestResult) {
  if (result.success) {
    return typeof result.durationMs === 'number'
      ? i18n.t('skill:connection.successDuration', { duration: formatNumber(result.durationMs) })
      : i18n.t('skill:connection.success');
  }
  const code = result.ownedFailureCode;
  const failureKeys = {
    SELECT_RUNTIME: 'connection.failures.SELECT_RUNTIME',
    RUNTIME_UNAVAILABLE: 'connection.failures.RUNTIME_UNAVAILABLE',
    EMPTY_CONFIG: 'connection.failures.EMPTY_CONFIG',
    INVALID_CONFIG_JSON: 'connection.failures.INVALID_CONFIG_JSON',
    SECRET_CRYPTO_UNAVAILABLE: 'connection.failures.SECRET_CRYPTO_UNAVAILABLE',
    CONNECTION_TIMEOUT: 'connection.failures.CONNECTION_TIMEOUT',
    CONNECTION_FAILED: 'connection.failures.CONNECTION_FAILED',
    LEGACY_RESULT_INCOMPATIBLE: 'connection.failures.LEGACY_RESULT_INCOMPATIBLE',
    TEST_INTERRUPTED: 'connection.failures.TEST_INTERRUPTED',
    RUNTIME_WAIT_TIMEOUT: 'connection.failures.RUNTIME_WAIT_TIMEOUT',
    INVALID_RESULT: 'connection.failures.INVALID_RESULT',
    REQUEST_CREATE_FAILED: 'connection.failures.REQUEST_CREATE_FAILED',
    DISPATCH_FAILED: 'connection.failures.DISPATCH_FAILED',
  } as const;
  const reason = code && Object.prototype.hasOwnProperty.call(failureKeys, code)
    && (code !== 'RUNTIME_WAIT_TIMEOUT' || typeof result.ownedFailureArgs?.seconds === 'number' && Number.isFinite(result.ownedFailureArgs.seconds))
    ? i18n.t(`skill:${failureKeys[code]}`, { seconds: formatNumber(result.ownedFailureArgs?.seconds ?? 0) })
    : result.message || i18n.t('skill:connection.unknown');
  return i18n.t('skill:connection.failureReason', { reason });
}

function detailAccessText(skill: Skill) {
  if (skill.sourceType === 'OSS_ZIP') {
    const lines = [
      i18n.t('skill:package.fileName', { name: skill.packageFileName || '-' }),
      i18n.t('skill:package.size', { size: skill.packageSize ? `${formatNumber(Math.ceil(skill.packageSize / 1024))} KB` : '-' }),
      i18n.t('skill:package.md5', { md5: skill.packageMd5 || '-' }),
    ];
    return lines.join('\n');
  }
  if (skill.type !== 'MCP') {
    return skill.installSpec || i18n.t('skill:access.noInstall');
  }
  try {
    const config = JSON.parse(skill.installSpec || '{}') as Record<string, unknown>;
    const headers = config.headers as Record<string, unknown> | undefined;
    if (headers) {
      config.headers = Object.fromEntries(Object.entries(headers).map(([name, value]) => [
        name, typeof value === 'object' && value !== null ? AUTHORIZATION_MASK : value,
      ]));
    }
    const env = config.env as Record<string, unknown> | undefined;
    if (env) config.env = Object.fromEntries(Object.entries(env).map(([name, value]) => [
      name, typeof value === 'object' && value !== null ? AUTHORIZATION_MASK : value,
    ]));
    return JSON.stringify(config, null, 2);
  } catch {
    return skill.installSpec || i18n.t('skill:access.noInstall');
  }
}

/** Tooltip 只透传子元素自身 props，Form.Item 注入的 checked/onChange 必须由组件显式接收再交给 Checkbox。 */
function SecretCheckbox({ checked, onChange, label, hint }: {
  checked?: boolean;
  onChange?: CheckboxProps['onChange'];
  label: string;
  hint: string;
}) {
  return (
    <Tooltip title={hint}>
      <Checkbox checked={checked} onChange={onChange} style={{ whiteSpace: 'nowrap' }}>{label}</Checkbox>
    </Tooltip>
  );
}

function mcpValues(raw: unknown) {
  return Object.entries((raw || {}) as Record<string, unknown>).map(([name, rawValue]) => {
    const secret = typeof rawValue === 'object' && rawValue !== null
      && ((rawValue as Record<string, unknown>).secret === true || (rawValue as Record<string, unknown>).kind === 'secretRef');
    return {
      name,
      secret,
      value: secret ? AUTHORIZATION_MASK : String(rawValue ?? ''),
    };
  });
}

function serializeMcpValues(entries: Array<{ name?: string; value?: string; secret?: boolean | string }> = []) {
  return Object.fromEntries(entries.filter((entry) => entry.name?.trim()).map((entry) => [
    entry.name!.trim(), (entry.secret === true || entry.secret === 'true')
      ? { secret: true, value: entry.value === AUTHORIZATION_MASK ? '' : (entry.value || '') }
      : (entry.value || ''),
  ]));
}

function pluginProviders(installSpec?: string): string[] {
  try {
    const parsed = JSON.parse(installSpec || '{}') as { providers?: string[] };
    return Array.isArray(parsed.providers) ? parsed.providers : [];
  } catch {
    return [];
  }
}
