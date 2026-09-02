import { Menu, Badge } from 'antd';
import {
  FileTextOutlined,
  RobotOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  BranchesOutlined,
  BookOutlined,
  BulbOutlined,
  ApartmentOutlined,
  NodeIndexOutlined,
  SettingOutlined,
  AuditOutlined,
  ShareAltOutlined,
  SafetyCertificateOutlined,
  LineChartOutlined,
  HistoryOutlined,
  ClockCircleOutlined,
  ApiOutlined,
  CompassOutlined,
  MessageOutlined,
  ExperimentOutlined,
} from '@ant-design/icons';
import { useNavigate, useLocation } from 'react-router-dom';
import type { MenuProps } from 'antd';
import type { ItemType } from 'antd/es/menu/interface';
import { useAgentPendingReviewCount } from '@/features/agent/hooks';
import { useMemoryPendingReviewCount } from '@/features/memory/hooks';
import { useScheduledTaskCapability } from '@/features/scheduledTask/hooks';
import { isScheduledTaskCapabilityQueryReady } from '@/features/scheduledTask/ScheduledTaskCapabilityGate';

export interface NavItem {
  key: string;
  label: string;
  icon: React.ReactNode;
  aliases?: string[];
  /** 入口不在菜单中渲染，但仍保留路由选中态与页头标题解析 */
  hidden?: boolean;
}

export interface NavGroup {
  key: string;
  label: string;
  items: NavItem[];
}

// 「系统设置」入口暂时隐藏，置回 true 即可恢复；路由与页面均保留。
const SETTINGS_MENU_ENABLED = false;

export const NAV_GROUPS: NavGroup[] = [
  {
    key: 'delivery',
    label: '交付',
    items: [
      { key: '/workitems', label: '工单', icon: <FileTextOutlined /> },
      { key: '/scheduled-tasks', label: '定时任务', icon: <ClockCircleOutlined /> },
      { key: '/executions', label: '执行记录', icon: <HistoryOutlined /> },
    ],
  },
  {
    key: 'workers-group',
    label: '数字员工',
    items: [
      { key: '/agents', label: '数字员工', icon: <RobotOutlined /> },
      { key: '/agents/reviews', label: '版本审核', icon: <SafetyCertificateOutlined /> },
      { key: '/squads', label: '小队', icon: <TeamOutlined /> },
      { key: '/executors', label: '执行器', icon: <ThunderboltOutlined /> },
    ],
  },
  {
    key: 'knowledge-group',
    label: '知识',
    items: [
      { key: '/repos', label: '仓库', icon: <BranchesOutlined /> },
      { key: '/repos/map', label: '仓库关系图', icon: <ShareAltOutlined /> },
      { key: '/memories', label: '记忆', icon: <BookOutlined /> },
      { key: '/skills', label: '能力', icon: <BulbOutlined /> },
    ],
  },
  {
    key: 'config-group',
    label: '配置',
    items: [
      { key: '/sdlcs', label: 'SDLC 流程', icon: <ApartmentOutlined /> },
      { key: '/status-templates', label: '状态模版', icon: <NodeIndexOutlined /> },
      { key: '/integrations', label: '工单平台集成', icon: <ApiOutlined /> },
      { key: '/integrations/channels', label: '消息渠道集成', icon: <MessageOutlined /> },
      {
        key: '/settings/members',
        label: '成员管理',
        icon: <TeamOutlined />,
        aliases: ['/settings/members-roles', '/settings/roles'],
      },
      { key: '/settings', label: '系统设置', icon: <SettingOutlined />, hidden: !SETTINGS_MENU_ENABLED },
    ],
  },
  {
    key: 'insight-group',
    label: '洞察',
    items: [
      { key: '/insights', label: '数据洞察', icon: <LineChartOutlined /> },
      { key: '/audit-logs', label: '审计日志', icon: <AuditOutlined /> },
      { key: '/evolution', label: '自进化', icon: <ExperimentOutlined />, hidden: true },
    ],
  },
  {
    key: 'about-group',
    label: '了解',
    items: [
      { key: '/about', label: '关于 AutoWonder', icon: <CompassOutlined /> },
    ],
  },
];

export function navItemMatchesPath(item: NavItem, pathname: string) {
  return [item.key, ...(item.aliases ?? [])].some((key) => pathname.startsWith(key));
}

export function buildMenuItems(
  badges?: Record<string, number>,
  collapsed?: boolean,
  scheduledTaskAvailable = false,
): ItemType[] {
  return NAV_GROUPS.map((group) => ({
    key: group.key,
    label: group.label,
    type: 'group' as const,
    children: group.items
      .filter((item) => !item.hidden && (item.key !== '/scheduled-tasks' || scheduledTaskAvailable))
      .map((item) => {
        const count = badges?.[item.key] ?? 0;
        const icon = count > 0 && collapsed
          ? <Badge dot><span>{item.icon}</span></Badge>
          : item.icon;
        const label = count > 0 && !collapsed
          ? <span>{item.label} <Badge count={count} overflowCount={99} style={{ marginLeft: 4 }} /></span>
          : item.label;
        return { key: item.key, icon, label };
      }),
  }));
}

export function resolveSelectedNavKey(pathname: string) {
  const allItems = NAV_GROUPS.flatMap((group) => group.items);
  return [...allItems]
    .filter((item) => navItemMatchesPath(item, pathname))
    .sort((a, b) => b.key.length - a.key.length)[0]?.key || '';
}

export function Sidebar({ collapsed = false }: { collapsed?: boolean }) {
  const navigate = useNavigate();
  const location = useLocation();

  const { data: agentCount = 0 } = useAgentPendingReviewCount();
  const { data: memoryCount = 0 } = useMemoryPendingReviewCount();
  const scheduledTaskCapability = useScheduledTaskCapability();

  const badges: Record<string, number> = {};
  if (agentCount > 0) badges['/agents/reviews'] = agentCount;
  if (memoryCount > 0) badges['/memories'] = memoryCount;

  const menuItems: MenuProps['items'] = buildMenuItems(
    badges,
    collapsed,
    isScheduledTaskCapabilityQueryReady(scheduledTaskCapability),
  ).map((item) =>
    collapsed && item && item.type === 'group' ? { ...item, label: '' } : item,
  );
  const selectedKey = resolveSelectedNavKey(location.pathname);

  return (
    <Menu
      mode="inline"
      inlineCollapsed={collapsed}
      selectedKeys={[selectedKey]}
      items={menuItems}
      onClick={({ key }) => navigate(key)}
      style={{ height: '100%', borderRight: 0 }}
    />
  );
}
