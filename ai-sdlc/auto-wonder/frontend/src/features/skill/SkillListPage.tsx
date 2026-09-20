import { useEffect, useMemo, useRef, useState } from 'react';
import type { Key, ReactNode } from 'react';
import {
  Table, Card, Collapse, Tag, Button, Space, Segmented, Modal, Form, Input, Select, Popconfirm, message,
  Radio, Alert, Typography, Descriptions, Divider, InputNumber, Checkbox, Tooltip, Tree, TreeSelect, Spin, Switch, Empty,
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, FolderOpenOutlined, FileTextOutlined,
  MinusCircleOutlined, DownloadOutlined, ApartmentOutlined,
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
import type { DataNode } from 'antd/es/tree';
import { buildDirectoryZip, buildPackageTree, formatBytes } from './skillPackage';
import { MarkdownView } from '@/shared/ui/MarkdownView';
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

const typeLabel: Record<Skill['type'], string> = {
  MCP: 'MCP 服务',
  SKILL: '技能',
  PLUGIN: '插件',
  HOOK: 'Runtime Hook',
};

const typeColor: Record<string, string> = {
  MCP: 'blue', SKILL: 'green', PLUGIN: 'orange', HOOK: 'purple',
};

const filterTypeOptions = [
  { value: '', label: '全部' },
  { value: 'SKILL', label: '技能' },
  { value: 'MCP', label: 'MCP 服务' },
  { value: 'HOOK', label: 'Runtime Hook' },
];

const creatableTypeOptions = [
  { value: 'SKILL', label: '技能' },
  { value: 'MCP', label: 'MCP 服务' },
  { value: 'HOOK', label: 'Runtime Hook' },
];

const MAX_SKILL_PACKAGE_BYTES = 100 * 1024 * 1024;
const skillPackageLimitHint = '最多 500 个文件；压缩包和解压后的总大小均不超过 100 MB。';
const AUTHORIZATION_MASK = '********';

// antd Select 对 null/undefined 值会按空值处理，分类下拉用字符串哨兵表示「未分类」，
// 提交时再换算回显式 null（取消打标）。
const UNCATEGORIZED = 'none';

// useQuery 未决/失败时的兜底必须是稳定引用：若每次渲染都新建 []，
// 依赖 categories 的 useEffect 会每轮都触发 setState，形成无限重渲染。
const NO_CATEGORIES: Category[] = [];

function accessLabel(record: Skill) {
  if (record.sourceType === 'OSS_ZIP') {
    if (record.type === 'HOOK') return 'Hook 包';
    return '能力包上传';
  }
  if (record.type === 'SKILL') {
    return '平台内置';
  }
  return '命令行接入';
}

/** 与后端 packageRef 的前置校验保持一致：只有存在 packageOssRef 的 OSS_ZIP 才有包可看。 */
function isPackageSkill(skill: Skill | null): skill is Skill {
  return !!skill && skill.sourceType === 'OSS_ZIP' && !!skill.packageOssRef;
}

function isMarkdown(path: string): boolean {
  return /\.(md|markdown)$/i.test(path);
}

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message ? error.message : '操作失败';
}

export function SkillListPage() {
  const queryClient = useQueryClient();
  const runWithAccess = useAccessCommand();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
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
  const selectedType = Form.useWatch('type', form);

  const [packageFiles, setPackageFiles] = useState<SkillPackageFile[]>([]);
  const [packageFilesLoading, setPackageFilesLoading] = useState(false);
  const [packageFilesError, setPackageFilesError] = useState<string | null>(null);
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
    { value: UNCATEGORIZED, label: '未分类' },
    ...categories.map((category) => ({
      value: String(category.id),
      label: category.path || category.name,
    })),
  ], [categories]);

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
      { value: UNCATEGORIZED, title: '无（顶级分类）', path: '无（顶级分类）' },
      ...filterNodes(categorySelectTree),
    ];
  }, [categories, categorySelectTree, editingCategoryId]);

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
    runWithAccess('ADMIN', '管理分类', () => {
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
            <Typography.Text type="secondary">{group.totalCount} 项</Typography.Text>
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
      return <Empty description="当前类型下暂无能力" />;
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
                  <Typography.Text strong>未分类</Typography.Text>
                  <Typography.Text type="secondary">{categoryGrouping.uncategorized.length} 项</Typography.Text>
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
          共 {groupedSkills.length} 条能力 · 按分类展示，类型筛选仍生效
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
        if (!cancelled) setPackageFilesError(errorMessage(error));
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
  const packageTree = useMemo(() => buildPackageTree(packageFiles), [packageFiles]);
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
      return <Typography.Text type="secondary">选择左侧文件查看内容</Typography.Text>;
    }
    if (selectedPackageEntry.dir) {
      const prefix = `${selectedPackagePath}/`;
      const childCount = packageFiles.filter((file) => file.path.startsWith(prefix)
        && !file.path.substring(prefix.length).includes('/')).length;
      return (
        <Space direction="vertical" size={4}>
          <Typography.Text strong>{selectedPackagePath}</Typography.Text>
          <Typography.Text type="secondary">目录 · {childCount} 项</Typography.Text>
        </Space>
      );
    }
    if (selectedPackageEntry.kind !== 'TEXT') {
      return (
        <Space direction="vertical" size={4}>
          <Typography.Text strong>{selectedPackageEntry.name}</Typography.Text>
          <Typography.Text type="secondary">
            该文件不支持在线预览（{selectedPackageEntry.kind === 'IMAGE' ? '图片' : '二进制'} · {formatBytes(selectedPackageEntry.size)}）
          </Typography.Text>
        </Space>
      );
    }
    if (packageFileLoading) {
      return <Spin />;
    }
    if (!packageFileContent) {
      return <Typography.Text type="secondary">内容加载失败</Typography.Text>;
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
  // 若在各 mutation 的 onSuccess 里各自处理，无法保证两条请求的先后与失败提示
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
      message.success('分类已删除');
    },
    onError: (error) => message.error(errorMessage(error)),
  });

  const deleteMut = useMutation({
    mutationFn: deleteSkill,
    onSuccess: () => {
      invalidate();
      setPendingDeleteId(null);
      message.success('已删除');
    },
  });

  const testConnectionMut = useMutation({
    mutationFn: ({ skillId, executorId }: { skillId: number; executorId?: number }) => testSkillConnection(skillId, executorId),
    onSuccess: (result, variables) => {
      setConnectionResults((prev) => ({ ...prev, [variables.skillId]: result }));
      if (result.success) {
        message.success(formatConnectionResult(result));
      } else {
        message.error(result.message || '连接失败');
      }
    },
    onError: (error, variables) => {
      const errorMessage = error instanceof Error ? error.message : '连接失败';
      setConnectionResults((prev) => ({ ...prev, [variables.skillId]: { success: false, message: errorMessage } }));
      message.error(errorMessage);
    },
    onSettled: () => setTestingSkillId(null),
  });

  const openCreate = () => {
    runWithAccess('READ_WRITE', '新增能力', () => {
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
    runWithAccess('READ_WRITE', '编辑能力', () => {
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
      message.error(`能力已保存，但分类设置失败：${errorMessage(error)}`);
    }
  };

  const handleSubmit = async () => {
    await runWithAccess('READ_WRITE', editingSkill ? '编辑能力' : '新增能力', async () => {
      if (accessMode === 'package') {
        if (packageReading || !zipFile) {
          message.error('请先选择并完成解析文件夹或 ZIP');
          return;
        }
        const values = await form.validateFields();
        const metadata = { type: values.type, name: values.name, description: values.description, providers: values.providers };
        try {
          const skill = editingSkill
            ? await updatePackageMut.mutateAsync({ id: editingSkill.id, file: zipFile, metadata })
            : await createPackageMut.mutateAsync({ file: zipFile, metadata });
          await applyCategoryTag(skill.id, values.categoryId);
          message.success(editingSkill ? '已覆盖上传' : '上传成功');
          closeForm();
        } catch (error) {
          message.error(errorMessage(error));
        }
        return;
      }
      const values = await form.validateFields();
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
        await applyCategoryTag(skill.id, values.categoryId);
        message.success(editingSkill ? '已保存' : '创建成功');
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
      if (!file.name.toLowerCase().endsWith('.zip')) throw new Error('请选择 ZIP 文件');
      if (file.size > MAX_SKILL_PACKAGE_BYTES) throw new Error(`压缩包超过 100 MB。${skillPackageLimitHint}`);
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
    runWithAccess('READ_WRITE', '测试 MCP 连接', () => {
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
    await runWithAccess('ADMIN', editingCategoryId ? '更新分类' : '创建分类', async () => {
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
        message.success(editingCategoryId ? '分类已保存' : '分类已创建');
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
    await runWithAccess('READ_WRITE', '批量设置能力分类', async () => {
      const categoryId = batchCategoryValue === UNCATEGORIZED ? null : Number(batchCategoryValue);
      setBatchApplying(true);
      try {
        const results: BatchSkillCategoryResult[] = await batchSetSkillCategory(
          selectedRowKeys.map(Number),
          categoryId,
        );
        const failed = results.filter((item) => !item.success);
        if (failed.length === 0) {
          message.success(`已为 ${results.length} 项能力设置分类`);
        } else {
          message.warning(
            `成功 ${results.length - failed.length} 项、失败 ${failed.length} 项：`
            + failed.map((item) => `#${item.skillId} ${item.message ?? ''}`.trim()).join('；'),
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
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '名称', dataIndex: 'name', width: 220 },
    { title: '描述', dataIndex: 'description', width: 320, ellipsis: true },
    {
      title: '类型', dataIndex: 'type', width: 90,
      render: (t: Skill['type']) => <Tag color={typeColor[t]}>{typeLabel[t]}</Tag>,
    },
    {
      title: '分类', key: 'category', width: 150,
      render: (_, record) => activeCategorySkillId === record.id ? (
        <TreeSelect
          autoFocus
          open
          onDropdownVisibleChange={(open) => { if (!open) setActiveCategorySkillId(null); }}
          onBlur={() => setActiveCategorySkillId(null)}
          onKeyDown={(event) => { if (event.key === 'Escape') setActiveCategorySkillId(null); }}
          aria-label={`${record.name}的分类`}
          style={{ width: 130 }}
          dropdownMatchSelectWidth={260}
          showSearch
          treeDefaultExpandAll
          treeLine
          treeNodeFilterProp="path"
          treeNodeLabelProp="path"
          placeholder="选择分类"
          allowClear
          value={record.categoryId == null ? undefined : String(record.categoryId)}
          treeData={categorySelectTree}
          loading={savingCategoryIds.includes(record.id)}
          disabled={savingCategoryIds.includes(record.id)}
          onChange={(value) => runWithAccess('READ_WRITE', '设置能力分类', async () => {
            setActiveCategorySkillId(null);
            setSavingCategoryIds((ids) => [...ids, record.id]);
            try {
              await setSkillCategory(record.id, value == null ? null : Number(value));
              await invalidate();
              message.success('分类已保存');
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
          aria-label={`修改${record.name}的分类`}
          title={record.categoryPath || '选择分类'}
          disabled={savingCategoryIds.includes(record.id)}
          onClick={() => runWithAccess('READ_WRITE', '设置能力分类', () => setActiveCategorySkillId(record.id))}
          style={{ border: 0, background: 'none', padding: 0, font: 'inherit',
            color: record.categoryId == null ? '#8c8c8c' : 'inherit', cursor: 'pointer',
            maxWidth: 130, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', textAlign: 'left' }}
        >
          {savingCategoryIds.includes(record.id) ? '保存中…' : (record.categoryPath || '—')}
        </button>
      ),
    },
    {
      title: '接入方式', dataIndex: 'installSpec', width: 140,
      render: (_, record) => accessLabel(record),
    },
    { title: '版本', dataIndex: 'version', width: 60 },
    {
      title: '更新时间', dataIndex: 'gmtModified', width: 170,
      render: (value: string | undefined) => formatDateTime(value),
    },
    {
      title: '更新人', dataIndex: 'modifierName', width: 120, ellipsis: true,
      render: (_, record) => record.modifierName || (record.modifierId ? `用户 #${record.modifierId}` : '-'),
    },
    {
      title: '操作', width: 330, fixed: 'right',
      render: (_, record) => {
        const connectionResult = connectionResults[record.id];
        return (
          <Space size={4} wrap>
            <Button type="link" size="small" icon={<FileTextOutlined />} onClick={() => setDetailSkill(record)}>详情</Button>
            {record.type === 'MCP' && (
              <Button
                type="link"
                size="small"
                loading={testingSkillId === record.id}
                onClick={() => handleTestConnection(record)}
              >
                测试连接
              </Button>
            )}
            {record.type === 'MCP' && connectionResult && (
              <Tag color={connectionResult.success ? 'success' : 'error'}>
                {formatConnectionResult(connectionResult)}
              </Tag>
            )}
            {record.type === 'MCP' && connectionResult?.success && connectionResult.tools && (
              <Button type="link" size="small" onClick={() => setToolListResult(connectionResult)}>
                查看 {connectionResult.tools.length} 个工具
              </Button>
            )}
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>
            <Popconfirm
              title="确认删除该技能吗？"
              open={pendingDeleteId === record.id}
              onOpenChange={(open) => {
                if (!open) setPendingDeleteId(null);
              }}
              onConfirm={() => runWithAccess(
                'READ_WRITE',
                '删除能力',
                () => deleteMut.mutate(record.id),
              )}
            >
              <Button
                type="link"
                size="small"
                danger
                icon={<DeleteOutlined />}
                onClick={() => runWithAccess(
                  'READ_WRITE',
                  '删除能力',
                  () => setPendingDeleteId(record.id),
                )}
              >
                删除
              </Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <>
      <Card
        title="能力库"
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增能力</Button>}
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
              <Typography.Text type="secondary">按分类展示</Typography.Text>
              <Switch
                checked={groupByCategory}
                onChange={handleToggleGroupByCategory}
                aria-label="按分类展示"
              />
            </Space>
            <Button icon={<ApartmentOutlined />} onClick={openCategoryManage}>管理分类</Button>
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
                  <Typography.Text type="secondary">已选 {selectedRowKeys.length} 项</Typography.Text>
                  <Button
                    size="small"
                    onClick={() => runWithAccess('READ_WRITE', '批量设置能力分类', () => setBatchCategoryOpen(true))}
                  >
                    批量设置分类
                  </Button>
                  <Button size="small" type="text" onClick={() => setSelectedRowKeys([])}>清除选择</Button>
                </Space>
              </div>
            )}
            <Table
              rowKey="id"
              columns={columns}
              dataSource={skills}
              loading={isLoading}
              rowSelection={{
                selectedRowKeys,
                onChange: (keys) => setSelectedRowKeys(keys),
              }}
              pagination={{
                current: page, pageSize: size, total,
                onChange: (p, ps) => { setPage(p); setSize(ps); },
                showTotal: (t) => `共 ${t} 条`,
              }}
              scroll={{ x: 1520 }}
            />
          </>
        )}
      </Card>

      <Modal
        title="选择测试 Runtime"
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
        <Alert type="info" showIcon message="MCP 将在所选 Runtime 本机执行，不会在服务端执行。" style={{ marginBottom: 16 }} />
        <Select
          style={{ width: '100%' }}
          value={testExecutorId}
          onChange={setTestExecutorId}
          placeholder="选择在线 Runtime"
          options={executors.filter((executor) => executor.status === 'ONLINE').map((executor) => ({
            value: executor.id,
            label: `${executor.name}（${executor.agentName || '未命名员工'} · #${executor.id}）`,
          }))}
          notFoundContent="没有在线 Runtime"
        />
      </Modal>
      <Modal
        title="能力详情"
        open={!!detailSkill}
        onCancel={closeDetail}
        footer={<Button onClick={closeDetail}>关闭</Button>}
        width={720}
      >
        {detailSkill && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="名称" span={2}>{detailSkill.name}</Descriptions.Item>
              <Descriptions.Item label="类型">
                <Tag color={typeColor[detailSkill.type]}>{typeLabel[detailSkill.type]}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="接入方式">{accessLabel(detailSkill)}</Descriptions.Item>
              <Descriptions.Item label="分类">{detailSkill.categoryPath || '未分类'}</Descriptions.Item>
              <Descriptions.Item label="版本">{detailSkill.version}</Descriptions.Item>
              <Descriptions.Item label="更新人">
                {detailSkill.modifierName || (detailSkill.modifierId ? `用户 #${detailSkill.modifierId}` : '-')}
              </Descriptions.Item>
              <Descriptions.Item label="更新时间" span={2}>{formatDateTime(detailSkill.gmtModified)}</Descriptions.Item>
            </Descriptions>
            <div>
              <Typography.Text strong>描述</Typography.Text>
              <div style={{
                marginTop: 8,
                padding: 12,
                background: '#fafafa',
                border: '1px solid #f0f0f0',
                borderRadius: 8,
                whiteSpace: 'pre-wrap',
              }}>
                {detailSkill.description || '暂无描述'}
              </div>
            </div>
            <div>
              <Typography.Text strong>{detailSkill.sourceType === 'OSS_ZIP' ? '上传包信息' : '安装/命令行接入'}</Typography.Text>
              <pre style={{
                marginTop: 8,
                padding: 12,
                background: '#111827',
                color: '#e5e7eb',
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
                  OSS Ref: {detailSkill.packageOssRef}
                </Typography.Text>
              </>
            )}
            {isPackageSkill(detailSkill) && (
              <>
                <Divider style={{ margin: 0 }} />
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <Typography.Text strong>包内容</Typography.Text>
                    <Button
                      size="small"
                      icon={<DownloadOutlined />}
                      loading={downloadingPackage}
                      onClick={handleDownloadPackage}
                    >
                      下载技能包
                    </Button>
                  </div>
                  {packageFilesError && (
                    <Alert type="error" showIcon style={{ marginTop: 8 }} message={packageFilesError} />
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
                          border: '1px solid #f0f0f0',
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
                          <Typography.Text type="secondary">技能包为空</Typography.Text>
                        )}
                      </div>
                      <div
                        data-testid="skill-package-preview"
                        style={{
                          flex: 1,
                          minWidth: 0,
                          maxHeight: 300,
                          overflow: 'auto',
                          border: '1px solid #f0f0f0',
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
        title={editingSkill ? '编辑能力' : '新增能力'}
        open={formOpen}
        width={680}
        forceRender
        onOk={handleSubmit}
        onCancel={closeForm}
        confirmLoading={packageReading || createMut.isPending || updateMut.isPending || createPackageMut.isPending || updatePackageMut.isPending}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="type" label="类型" rules={[{ required: true }]}>
			<Select disabled={!!editingSkill} onChange={(value) => {
              packageSelectionSeq.current += 1;
              setPackageReading(false);
              setZipFile(null);
              setAccessMode(value === 'PLUGIN' || value === 'HOOK' ? 'package' : 'manual');
            }}
              options={creatableTypeOptions} />
          </Form.Item>
          {(selectedType === 'SKILL' || selectedType === 'PLUGIN' || selectedType === 'HOOK') && (
            <Form.Item label="接入方式">
              <Radio.Group
                value={accessMode}
                onChange={(e) => {
                  setAccessMode(e.target.value);
                  packageSelectionSeq.current += 1;
                  setPackageReading(false);
                  setZipFile(null);
                }}
                options={selectedType === 'PLUGIN' || selectedType === 'HOOK'
                  ? [{ value: 'package', label: selectedType === 'HOOK' ? '上传 Hook 文件夹 / ZIP' : '上传插件文件夹 / ZIP' }]
                  : [{ value: 'manual', label: '手动接入' }, { value: 'package', label: '上传文件夹 / ZIP' }]}
              />
            </Form.Item>
          )}
          {accessMode === 'package' && selectedType !== 'MCP' && (
            <Form.Item label="能力包" required>
              <Space direction="vertical" style={{ width: '100%' }}>
                <Alert type="info" showIcon message="上传限制" description={skillPackageLimitHint} />
                <Space>
                  <Button icon={<FolderOpenOutlined />} onClick={() => fileInputRef.current?.click()}>
                    选择文件夹
                  </Button>
                  <Button icon={<FileTextOutlined />} onClick={() => zipInputRef.current?.click()}>
                    选择 ZIP
                  </Button>
                </Space>
                <input
                  ref={(node) => {
                    fileInputRef.current = node;
                    node?.setAttribute('webkitdirectory', '');
                    node?.setAttribute('directory', '');
                  }}
                  aria-label="选择能力文件夹"
                  type="file"
                  multiple
                  style={{ display: 'none' }}
                  onChange={(e) => handlePackageSelectFiles(e.target.files, true)}
                />
                <input
                  ref={zipInputRef}
                  aria-label="选择能力 ZIP"
                  type="file"
                  accept=".zip,application/zip"
                  style={{ display: 'none' }}
                  onChange={(e) => handlePackageSelectFiles(e.target.files, false)}
                />
                {packageReading ? <Spin /> : zipFile ? (
                  <Alert type="success" showIcon message={`已选择 ${zipFile.name}`} description={formatBytes(zipFile.size)} />
                ) : (
                  <Alert type="info" showIcon
                    message={selectedType === 'SKILL' ? '请选择根目录包含 SKILL.md 的文件夹或 ZIP'
                      : selectedType === 'HOOK' ? '请选择根目录包含 hook.yaml 的文件夹或 ZIP' : '请选择插件文件夹或 ZIP'}
                    description={editingSkill?.sourceType === 'OSS_ZIP'
                      ? `当前包：${editingSkill.packageFileName || editingSkill.packageOssRef || '已上传'}，重新选择文件夹或 ZIP 后会覆盖上传。`
                      : selectedType === 'SKILL' ? '系统会读取 SKILL.md 顶部 YAML frontmatter 中的 name 和 description。' : undefined}
                  />
                )}
              </Space>
            </Form.Item>
          )}
          {selectedType === 'PLUGIN' && (
            <Form.Item name="providers" label="适用 Provider" rules={[{ required: true, message: '请选择 Provider' }]}>
              <Select mode="multiple" options={[{ value: 'claude' }, { value: 'qoder' }]} />
            </Form.Item>
          )}
          <Form.Item name="name" label="名称"
            rules={selectedType === 'HOOK' ? [] : [{ required: true, message: '请输入能力名称' }]}>
            <Input disabled={accessMode === 'package' && selectedType === 'SKILL'} placeholder="如: code-review-mcp" />
          </Form.Item>
          <Form.Item
            name="categoryId"
            label="分类标签（选填）"
            initialValue={UNCATEGORIZED}
            extra="选择“未分类”即取消打标，不改变能力内容、类型或绑定关系。"
          >
            <Select options={categorySelectOptions} placeholder="未分类" />
          </Form.Item>
          {selectedType === 'MCP' && (
            <>
              <Form.Item name="mcpTransport" label="服务器类型" initialValue="http">
                <Select options={[
                  { value: 'http', label: 'Streamable HTTP' },
                  { value: 'sse', label: 'SSE' },
                  { value: 'stdio', label: '本地命令（STDIO）' },
                ]} />
              </Form.Item>
              <Form.Item noStyle shouldUpdate={(prev, next) => prev.mcpTransport !== next.mcpTransport}>
                {({ getFieldValue }) => getFieldValue('mcpTransport') === 'stdio' ? (
                  <>
                    <Form.Item name="mcpCommand" label="可执行文件" rules={[{ required: true }]}><Input placeholder="如 npx" /></Form.Item>
                    <Form.List name="mcpArgs">
                      {(fields, { add, remove }) => <Form.Item label="参数（可选）">
                        {fields.map((field) => <div key={field.key} style={{ display: 'flex', width: '100%', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                          <Form.Item {...field} name={[field.name, 'value']} rules={[{ required: true, message: '请输入参数' }]} style={{ flex: 1, minWidth: 0, marginBottom: 0 }}>
                            <Input placeholder="如 --server-url" />
                          </Form.Item>
                          <MinusCircleOutlined aria-label="删除参数" onClick={() => remove(field.name)} />
                        </div>)}
                        <Button block type="dashed" onClick={() => add()} icon={<PlusOutlined />}>添加参数</Button>
                      </Form.Item>}
                    </Form.List>
                    <Form.List name="mcpEnv">
                      {(fields, { add, remove }) => <Form.Item label="Env（可选）">
                        {fields.map((field) => <div key={field.key} style={{ display: 'flex', width: '100%', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                          <Form.Item {...field} name={[field.name, 'name']} rules={[{ required: true, message: '请输入变量名' }]} style={{ flex: '0 1 240px', minWidth: 0, marginBottom: 0 }}>
                            <Input placeholder="变量名" />
                          </Form.Item>
                          <Form.Item {...field} name={[field.name, 'value']} rules={[{ required: true, message: '请输入变量值' }]} style={{ flex: 1, minWidth: 0, marginBottom: 0 }}>
                            <Input placeholder="变量值" autoComplete="new-password" onFocus={(event) => { if (form.getFieldValue(['mcpEnv', field.name, 'secret']) && event.currentTarget.value === AUTHORIZATION_MASK) form.setFieldValue(['mcpEnv', field.name, 'value'], ''); }} />
                          </Form.Item>
                          <Form.Item {...field} name={[field.name, 'secret']} valuePropName="checked" style={{ flex: 'none', marginBottom: 0 }}><Tooltip title="加密保存；编辑时只能填写新值"><Checkbox style={{ whiteSpace: 'nowrap' }} onChange={(event) => form.setFieldValue(['mcpEnv', field.name, 'secret'], event.target.checked)}>私密</Checkbox></Tooltip></Form.Item>
                          <MinusCircleOutlined aria-label="删除 Env" onClick={() => remove(field.name)} />
                        </div>)}
                        <Button block type="dashed" onClick={() => add()} icon={<PlusOutlined />}>添加 Env</Button>
                      </Form.Item>}
                    </Form.List>
                  </>
                ) : (
                  <>
                    <Form.Item name="mcpUrl" label="HTTPS 地址" rules={[{ required: true, type: 'url' }]}><Input placeholder="https://example.com/mcp" /></Form.Item>
                    <Form.List name="mcpHeaders">
                      {(fields, { add, remove }) => (
                        <Form.Item label="Headers（可选）">
                          {fields.map((field) => (
                            <div key={field.key} style={{ display: 'flex', width: '100%', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                              <Form.Item {...field} name={[field.name, 'name']} rules={[{ required: true, message: '请输入 Header 名称' }]} style={{ flex: '0 1 180px', minWidth: 0, marginBottom: 0 }}>
                                <Input placeholder="Authorization" />
                              </Form.Item>
                              <Form.Item {...field} name={[field.name, 'value']} rules={[{ required: true, message: '请输入 Header 值' }]} style={{ flex: 1, minWidth: 0, marginBottom: 0 }}>
                                <Input
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
                                <Tooltip title="加密保存；编辑时只能填写新值"><Checkbox style={{ whiteSpace: 'nowrap' }} onChange={(event) => form.setFieldValue(['mcpHeaders', field.name, 'secret'], event.target.checked)}>私密</Checkbox></Tooltip>
                              </Form.Item>
                              <MinusCircleOutlined aria-label="删除 Header" onClick={() => remove(field.name)} />
                            </div>
                          ))}
                          <Button block type="dashed" onClick={() => add()} icon={<PlusOutlined />}>添加 Header</Button>
                        </Form.Item>
                      )}
                    </Form.List>
                    <Form.Item name="mcpTimeoutSeconds" label="超时时间（秒）" initialValue={60}
                      rules={[{ required: true, message: '请输入超时时间' }]} extra="连接、工具列表获取和工具调用的超时时间，范围 1–600 秒。">
                      <InputNumber min={1} max={600} precision={0} style={{ width: '100%' }} />
                    </Form.Item>
                  </>
                )}
              </Form.Item>
            </>
          )}
          {accessMode === 'manual' && selectedType === 'SKILL' && (
            <Form.Item name="installSpec" label="安装/下载方式" rules={[{ required: true, message: '请填写安装方式' }]}
              tooltip="如 npx @anthropic/mcp-server 或 pip install xxx">
              <Input.TextArea rows={2} placeholder="如: npx @anthropic/mcp-server-github" />
            </Form.Item>
          )}
          <Form.Item name="description" label="描述">
            <Input.TextArea disabled={accessMode === 'package' && selectedType === 'SKILL'} rows={2} placeholder="描述该能力的功能" />
          </Form.Item>
        </Form>
      </Modal>
      <Modal title={`MCP 工具列表（${toolListResult?.tools?.length || 0}）`} open={toolListResult !== null}
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
        {toolListResult?.tools?.length === 0 && <Typography.Text type="secondary">该 MCP 未返回工具。</Typography.Text>}
      </Modal>

      <Modal
        title="管理分类"
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
              border: '1px solid #f0f0f0',
              borderRadius: 8,
              padding: 12,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
              <Typography.Text type="secondary">分类目录</Typography.Text>
              <Button
                size="small"
                icon={<PlusOutlined />}
                onClick={() => runWithAccess('ADMIN', '创建分类', () => startCreateCategory(editingCategoryId))}
              >
                新增分类
              </Button>
            </div>
            {categories.length === 0 ? (
              <Typography.Text type="secondary">还没有分类，先在右侧新增一个。</Typography.Text>
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
                          title={`确定删除分类「${node.title}」？`}
                          description="有子分类或关联能力时，请先迁移后再删除。"
                          open={pendingDeleteCategoryId === node.key}
                          onOpenChange={(open) => { if (!open) setPendingDeleteCategoryId(null); }}
                          onConfirm={() => runWithAccess('ADMIN', '删除分类',
                            () => handleDeleteCategory(Number(node.key)))}
                          okText="删除"
                          cancelText="取消"
                          okButtonProps={{ danger: true, loading: deleteCategoryMut.isPending }}
                        >
                          <Button type="text" size="small" danger icon={<DeleteOutlined />}
                            aria-label={`删除分类「${node.title}」`}
                            title="删除分类"
                            loading={deleteCategoryMut.isPending}
                            onClick={() => runWithAccess('ADMIN', '删除分类',
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
              未分类为系统视图，不是分类节点。
            </Typography.Text>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {editingCategoryId != null
                ? `当前：${categories.find((item) => item.id === editingCategoryId)?.path ?? ''}`
                : '新增分类'}
            </Typography.Text>
            <Form form={categoryForm} layout="vertical" style={{ marginTop: 8 }}>
              <Form.Item
                name="name"
                label="分类名称"
                rules={[
                  { required: true, message: '请输入分类名称' },
                  { max: 50, message: '名称不超过 50 个字符' },
                ]}
              >
                <Input maxLength={50} placeholder="如: Vue" showCount />
              </Form.Item>
              <Form.Item
                name="parentId"
                label="上级分类"
                initialValue={UNCATEGORIZED}
                extra="选择“无（顶级分类）”创建顶级分类；选择已有节点创建子分类。"
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
                label="分类说明（选填）"
                extra="供上传人和调用 MCP 的智能体判断适用范围。"
              >
                <Input.TextArea rows={3} maxLength={1000} placeholder="说明该分类的适用范围与排除项" />
              </Form.Item>
              <Form.Item style={{ marginBottom: 0 }}>
                <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center',
                  borderTop: '1px solid #f0f0f0', paddingTop: 16 }}>
                  <Space>
                    <Button onClick={() => setCategoryManageOpen(false)}>取消</Button>
                    <Button
                      type="primary"
                      loading={createCategoryMut.isPending || updateCategoryMut.isPending}
                      onClick={handleCategorySubmit}
                    >
                      保存分类
                    </Button>
                  </Space>
                </div>
              </Form.Item>
            </Form>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              修改名称或说明不改变已有能力的分类关联。
            </Typography.Text>
          </div>
        </div>
      </Modal>

      <Modal
        title="批量设置分类"
        open={batchCategoryOpen}
        onCancel={() => setBatchCategoryOpen(false)}
        onOk={handleBatchApply}
        confirmLoading={batchApplying}
        okText="应用"
        width={520}
      >
        <Typography.Paragraph type="secondary">
          将为已选的 {selectedRowKeys.length} 项能力设置分类；选择“未分类”将取消其分类关联。
        </Typography.Paragraph>
        <Form layout="vertical">
          <Form.Item label="目标分类">
            <Select
              value={batchCategoryValue}
              onChange={setBatchCategoryValue}
              options={categorySelectOptions}
              aria-label="目标分类"
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

function formatDateTime(value?: string) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('zh-CN', { hour12: false });
}

function formatConnectionResult(result: SkillConnectionTestResult) {
  if (result.success) {
    return `连接成功${typeof result.durationMs === 'number' ? `（${result.durationMs}ms）` : ''}`;
  }
  return `连接失败：${result.message || '未知错误'}`;
}

function detailAccessText(skill: Skill) {
  if (skill.sourceType === 'OSS_ZIP') {
    const lines = [
      `文件名: ${skill.packageFileName || '-'}`,
      `大小: ${skill.packageSize ? `${Math.ceil(skill.packageSize / 1024)} KB` : '-'}`,
      `MD5: ${skill.packageMd5 || '-'}`,
    ];
    return lines.join('\n');
  }
  if (skill.type !== 'MCP') {
    return skill.installSpec || '暂无安装/下载方式';
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
    return skill.installSpec || '暂无安装/下载方式';
  }
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
