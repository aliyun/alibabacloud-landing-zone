# AutoWonder Community 0.3.4

- Release date: 2026-08-07
- Version change: `0.3.3` -> `0.3.4`
- Bump rationale: backward-compatible upgrade planner fix
- Internal master baseline: `3262a46f66ac5087261429250091bfea5a61d12b`
- Release-parent Community commit: `94f8204f2294f5a2b30efd08df8e1734e9439912`
- Public repository base: `811645c96c7af117be5de158ae773b0ada6e6014`
- Public output branch: `fix/autowonder-upgrade-env-scanner-20260807`

## Fixes

- Makes upgrade environment scanning aware of the source file type.
- Excludes Shell-local assignments such as `IFS`, `LC_ALL`, `SCRIPT_DIR`,
  `TEMP_FILES`, and `TEMP_DIRS` from the application environment contract.
- Preserves explicit `${KEY...}` references and `application.env.example`
  declarations.

## Upgrade And Data Impact

- No application database, infrastructure, runtime, or environment contract
  change.
- Regenerate an upgrade plan that was blocked by Shell-local variable names;
  never add those names to the deployed application environment file.

## Verification

- Upgrade planner regression and full deployment Skill tests passed.
- The real `7af9399` to `dd3e6d8` upgrade interval produces no false added keys
  or blocked reasons while retaining the runtime-version change signal.

## Handoff

- [Create GitHub upstream PR](https://github.com/aliyun/alibabacloud-landing-zone/compare/master...caihe-ch:fix/autowonder-upgrade-env-scanner-20260807?expand=1)
