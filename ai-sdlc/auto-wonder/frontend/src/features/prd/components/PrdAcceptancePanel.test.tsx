import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { PrdAcceptancePanel } from './PrdAcceptancePanel';
import type { PrdDeliveryAcceptance, RequirementAcceptance } from '../api';

// antd's built-in table strings (row expand label) come from its own locale, so the test mounts the
// same ConfigProvider the app uses; otherwise the label falls back to English and the query misses.
function renderPanel(acceptance: PrdDeliveryAcceptance | null) {
  return render(
    <ConfigProvider locale={zhCN}>
      <PrdAcceptancePanel acceptance={acceptance} />
    </ConfigProvider>,
  );
}

function row(overrides: Partial<RequirementAcceptance> = {}): RequirementAcceptance {
  return {
    id: 1,
    dispatchId: 77,
    designVersionId: 5001,
    requirementKey: 'REQ-001',
    requirementTitle: '上传 CSV',
    priority: 'P0',
    excluded: false,
    implementation: 'NOT_IMPLEMENTED',
    verdict: 'NOT_VERIFIED',
    note: null,
    notApplicableReason: null,
    evidence: [],
    verified: false,
    reportedAt: null,
    ...overrides,
  };
}

function acceptance(requirements: RequirementAcceptance[]): PrdDeliveryAcceptance {
  const passed = requirements.filter((r) => r.verdict === 'PASSED').length;
  const failed = requirements.filter((r) => r.verdict === 'FAILED').length;
  const notApplicable = requirements.filter((r) => r.verdict === 'NOT_APPLICABLE').length;
  const notVerified = requirements.length - passed - failed - notApplicable;
  return {
    dispatchId: 77,
    designVersionId: 5001,
    versionNo: 2,
    designSha256: 'd'.repeat(64),
    targetRepoId: 42,
    targetAppRoot: 'frontend',
    targetCommitSha: 'c'.repeat(40),
    requirements,
    total: requirements.length,
    passed,
    failed,
    notVerified,
    notApplicable,
    complete: notVerified === 0,
  };
}

describe('PrdAcceptancePanel', () => {
  it('renders a seeded row as not verified and never as passed', () => {
    renderPanel(acceptance([row()]));

    // The default seeded state must read as "not verified", which is the whole point of seeding.
    expect(screen.getByText('未验证')).toBeInTheDocument();
    expect(screen.queryByText('通过')).not.toBeInTheDocument();
    expect(screen.getByText('REQ-001')).toBeInTheDocument();
  });

  it('warns that the delivery is not accepted while anything is unverified', () => {
    renderPanel(acceptance([row(), row({ id: 2, requirementKey: 'REQ-002' })]));

    expect(screen.getByText('尚有需求未验证')).toBeInTheDocument();
    expect(screen.queryByText('全部需求已验收通过')).not.toBeInTheDocument();
  });

  it('reports failure separately from not-verified once every row has a verdict', () => {
    const data = acceptance([
      row({ verdict: 'PASSED', verified: true, implementation: 'IMPLEMENTED' }),
      row({ id: 2, requirementKey: 'REQ-002', verdict: 'FAILED', verified: true, implementation: 'PARTIAL', note: '超过 10MB 未拒绝' }),
    ]);
    renderPanel(data);

    expect(screen.getByText('有 1 条需求未通过')).toBeInTheDocument();
    expect(screen.getByText('通过')).toBeInTheDocument();
    expect(screen.getByText('未通过')).toBeInTheDocument();
  });

  it('shows all-passed only when complete and nothing failed', () => {
    const data = acceptance([
      row({ verdict: 'PASSED', verified: true, implementation: 'IMPLEMENTED' }),
    ]);
    renderPanel(data);

    expect(screen.getByText('全部需求已验收通过')).toBeInTheDocument();
    expect(screen.queryByText('尚有需求未验证')).not.toBeInTheDocument();
  });

  it('marks an excluded requirement and renders its design reason rather than the raw marker', async () => {
    const user = userEvent.setup();
    const data = acceptance([
      row({ verdict: 'NOT_APPLICABLE', verified: true, excluded: true, notApplicableReason: 'EXCLUDED_BY_CONFIRMED_DESIGN' }),
    ]);
    renderPanel(data);

    expect(screen.getByText('本次排除')).toBeInTheDocument();
    expect(screen.getByText('不适用')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '展开行' }));
    // The stable marker is an internal token; the user must see the explanation instead.
    expect(await screen.findByText('确认的设计已将本需求排除在本次交付之外')).toBeInTheDocument();
    expect(screen.queryByText('EXCLUDED_BY_CONFIRMED_DESIGN')).not.toBeInTheDocument();
  });

  it('links artifact evidence to a presigned url and shows non-artifact evidence as text', async () => {
    const user = userEvent.setup();
    const data = acceptance([
      row({
        verdict: 'PASSED',
        verified: true,
        implementation: 'IMPLEMENTED',
        evidence: [
          { kind: 'ARTIFACT', ref: '901', title: '验收截图', url: 'https://oss.test/evidence.png?sig=1' },
          { kind: 'MR', ref: 'https://code.test/mr/9', title: null, url: null },
        ],
      }),
    ]);
    renderPanel(data);

    await user.click(screen.getByRole('button', { name: '展开行' }));

    // Artifact evidence resolves to a presigned link the reviewer can open; other kinds stay as refs.
    const link = screen.getByRole('link', { name: '验收截图' });
    expect(link).toHaveAttribute('href', 'https://oss.test/evidence.png?sig=1');
    expect(screen.getByText('https://code.test/mr/9')).toBeInTheDocument();
  });

  it('renders an empty state when there is no acceptance data', () => {
    renderPanel(null);

    expect(screen.getByText('尚无验收结果')).toBeInTheDocument();
  });
});
