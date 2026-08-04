import './AboutAutoWonderPage.css';

const roleBadges = [
  { label: 'PM', className: 'about-aw-role-pm' },
  { label: 'DEV', className: 'about-aw-role-dev' },
  { label: 'CR', className: 'about-aw-role-cr' },
  { label: 'QA', className: 'about-aw-role-qa' },
];

const agentProgress = [
  { name: 'Agent Dev', status: '完成 13m', percent: 100, className: 'about-aw-agent-dev' },
  { name: 'Agent CR', status: '执行中', percent: 68, className: 'about-aw-agent-cr' },
  { name: 'Agent Testing', status: '等待交接', percent: 0, className: 'about-aw-agent-testing' },
];

const metricCards = [
  { value: '7', label: '执行事件' },
  { value: '3', label: '产物' },
  { value: '2', label: '记忆候选' },
];

const quickStartSteps = [
  {
    number: '01',
    icon: '🔌',
    title: '工单系统集成',
    description: '连接 Aone / Jira / 自建工单，让需求、Bug、任务成为调度入口。',
    className: 'about-aw-step-dark',
  },
  {
    number: '02',
    icon: '🧭',
    title: '托管仓库',
    description: '录入仓库、权限和背景，AI 扫描形成结构化项目认知。',
  },
  {
    number: '03',
    icon: '👥',
    title: '设置角色与小队',
    description: '定义 Dev、CR、QA、PM 等数字员工职责与技能。',
  },
  {
    number: '04',
    icon: '🧩',
    title: '绑定 SDLC',
    description: '把分析、编码、自测、交接、验收变成可调度步骤。',
    className: 'about-aw-step-orange',
  },
  {
    number: '05',
    icon: '⚡',
    title: '开始工单执行',
    description: '执行器接单，按在线状态与身份拉起真实 Agent 工作。',
  },
  {
    number: '06',
    icon: '🌱',
    title: '沉淀记忆与产物',
    description: '交付产物可追溯，经验成为组织和数字员工记忆。',
    className: 'about-aw-step-green',
  },
];

const principles = [
  {
    label: '理念 01',
    title: '不是聊天机器人，而是研发编排系统',
    description: 'AutoWonder 关注的是“谁在什么上下文里，用什么技能，按什么流程，交付什么产物”。',
    className: 'about-aw-principle-dark',
  },
  {
    label: '理念 02',
    title: '数字员工需要小队协作',
    description: '开发、评审、测试、需求澄清可以由不同 Agent 接力，页面能追踪每个 Agent 的 SDLC 进度。',
  },
  {
    label: '理念 03',
    title: '每次交付都让组织更聪明',
    description: '工单过程、产物、失败原因、项目经验和偏好，都会进入可审核、可复用的知识沉淀链路。',
    className: 'about-aw-principle-green',
  },
];

export function AboutAutoWonderPage() {
  return (
    <div className="about-aw-page">
      <span className="about-aw-sr-only">关于 AutoWonder</span>

      <section className="about-aw-hero">
        <div className="about-aw-hero-copy">
          <div className="about-aw-eyebrow">AutoWonder · Autonomous SDLC Agent Platform</div>
          <h1>
            把工单交给一支
            <br />
            会协作、会沉淀的数字员工小队
          </h1>
          <p>
            AutoWonder 将工单系统、代码仓库、SDLC 流程、数字员工、执行器和组织记忆连接成一个闭环。
            你定义工作方式，数字员工按小队协作推进研发任务，并把过程、产物和经验持续沉淀回来。
          </p>
          <div className="about-aw-hero-actions">
            <div className="about-aw-primary-cta">5 分钟理解工作流</div>
            <div className="about-aw-secondary-cta">从工单到产物的自动交付闭环</div>
          </div>
        </div>

        <div className="about-aw-preview-wrap">
          <div className="about-aw-preview-card" aria-label="工单执行示意">
            <div className="about-aw-ticket-head">
              <div>
                <div className="about-aw-ticket-title">工单 #30020 · 支付回调稳定性修复</div>
                <div className="about-aw-ticket-source">来自 Aone / Jira / 自建工单系统</div>
              </div>
              <div className="about-aw-live-dot">执行中</div>
            </div>

            <div className="about-aw-delivery-grid">
              <div className="about-aw-role-column">
                {roleBadges.map((role) => (
                  <div className={`about-aw-role-badge ${role.className}`} key={role.label}>
                    {role.label}
                  </div>
                ))}
              </div>

              <div className="about-aw-progress-column">
                {agentProgress.map((agent) => (
                  <div className={`about-aw-agent-card ${agent.className}`} key={agent.name}>
                    <div className="about-aw-agent-meta">
                      <span>{agent.name}</span>
                      <span>{agent.status}</span>
                    </div>
                    <div className="about-aw-progress-track">
                      <div className="about-aw-progress-fill" style={{ width: `${agent.percent}%` }} />
                    </div>
                  </div>
                ))}

                <div className="about-aw-metrics">
                  {metricCards.map((metric) => (
                    <div className="about-aw-metric-card" key={metric.label}>
                      <b>{metric.value}</b>
                      <br />
                      {metric.label}
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="about-aw-quick-start">
        <div className="about-aw-section-head">
          <div>
            <div className="about-aw-section-kicker">QUICK START</div>
            <h2>从现有研发体系接入 AutoWonder</h2>
          </div>
          <p>这条路径兼容 Aone、Jira、GitLab/GitHub、自建仓库与独立执行器，不要求推翻现有流程。</p>
        </div>

        <div className="about-aw-steps">
          {quickStartSteps.map((step) => (
            <article className={`about-aw-step-card ${step.className || ''}`} key={step.number}>
              <div className="about-aw-step-number">{step.number}</div>
              <div className="about-aw-step-icon" aria-hidden="true">{step.icon}</div>
              <h3>{step.title}</h3>
              <p>{step.description}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="about-aw-principles">
        {principles.map((principle) => (
          <article className={`about-aw-principle-card ${principle.className || ''}`} key={principle.label}>
            <div>{principle.label}</div>
            <h3>{principle.title}</h3>
            <p>{principle.description}</p>
          </article>
        ))}
      </section>
    </div>
  );
}
