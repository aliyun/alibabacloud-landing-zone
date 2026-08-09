# AutoWonder Community 0.3.5

- Release date: 2026-08-09
- Version change: `0.3.4` -> `0.3.5`
- Bump rationale: backward-compatible operational safety fix
- Full internal master baseline: `3262a46f66ac5087261429250091bfea5a61d12b`
- Reviewed fix parent: `b1916d4b5732854278b34da14bc03e7379ac8ad6`
- Internal fix commit: `73dec919b86bbf07a91f8886e6b817d35dafbfae`
- Release-parent Community commit: `a6c94b9674193c65ba09314bebc06cbfb1bcfe6d`
- Public repository base: `811645c96c7af117be5de158ae773b0ada6e6014`
- Public output branch: `fix/autowonder-log-retention-20260809`

## Fixes

- Rolls local application logs daily or when the active file reaches 50 MB.
- Deletes only matching compressed archives older than 14 days or beyond the
  newest cumulative 5 GB per node.
- Leaves the active log, unrelated files, nested paths, and link targets alone.

## Upgrade And Data Impact

- No database, environment variable, infrastructure, API, or frontend change.
- Restart the application after replacing the JAR so Log4j2 loads the policy.
- Systemd journal retention remains a separate host-level responsibility.

## Verification

- The focused retention contract test passed.
- All 1,823 backend tests passed on the internal fix branch.
- Log4j2 emitted no plugin-resolution error for the new rollover components.

## Handoff

- [Create internal code review](https://code.alibaba-inc.com/sdlc-autopilot/auto-wonder/codereview/new?from=master&to=fix/log4j2-log-retention-20260809)
- [Create GitHub upstream PR](https://github.com/aliyun/alibabacloud-landing-zone/compare/master...caihe-ch:fix/autowonder-log-retention-20260809?expand=1)
