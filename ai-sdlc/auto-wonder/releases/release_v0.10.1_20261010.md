# AutoWonder Community v0.10.1

Version: 0.10.0 → 0.10.1 (PATCH). Fixes service startup when SLS is enabled.

## Baselines

Community base: `5af9b768a9d9ada5898561321bd599f178bf202b` (v0.10.0).
Previous and new incorporated master baseline: `cc41f31632dd7ab5f5a8bd7d7916962445dcd02e`; this patch does not synchronize additional master changes.

## Fix

The SLS SDK still uses the Fastjson v1 binary API after business JSON moved to Jackson. Restore that API through the Fastjson 2 compatibility artifact `com.alibaba:fastjson:2.0.65`, with runtime scope. Business code continues to use Jackson; SDK versions, SLS logging failure behavior and the existing dependency exclusion are unchanged.

The live JSON regression also exposed clarification replies being sent to an external channel sink that does not exist for this browser-backed channel. Keep reply persistence, completion events and resume sessions, while skipping that external delivery only for workitem clarification. Other channels retain their existing delivery behavior.

## Regression Coverage

- Enabled SLS appender/business initialization and real SDK HTTP success/error JSON parsing.
- Shared empty/populated objects, numeric map keys, literal `@type`/`$ref` strings and Unicode/escape preservation through Jackson.
- `e2e-tests/verify.sh --start --with-sls` starts the real application with an isolated local SLS protocol receiver. The standard check requires system, business and metric HTTP receipts.
- Every standard check injects empty/populated initial/resumed clarification turns over real WebSocket connections, with reply ACKs and exact HTTP and wire-value assertions. This uses a scripted executor; `--with-runtime` separately exercises a real Qoder executor and downloaded artifact.

## Upgrade And Data Impact

None — backward-compatible with the previous release. Replace the application artifact using the supported upgrade workflow. Sites that temporarily disabled SLS can restore `AUTOWONDER_SLS_ENABLED=true` with their existing valid SLS configuration after deploying this fix.

## DDL/DML/Migration Impact

No DDL/DML change. No new migrations or changes to published migrations.

## Configuration And Deployment Impact

No new production environment variables, cloud resources or credentials. SLS remains disabled by default; deployment/upgrade automation retains its existing SLS-enabled contract. The new receiver belongs only to the opt-in E2E Compose profile, with no published host port. Recommended executor runtime remains 0.3.3.

## Verification

The release gate requires the complete backend suite, frontend tests/lint/build, E2E harness tests, SLS-off and SLS-on image lifecycle checks, JSON wire injection and real runtime dispatch. The final measured results, exact source SHA and review disposition are recorded in the release quality evidence and MR before publication; no pending check is claimed as passed here.

## Scope And Risks

The local SLS fixture proves SDK startup and HTTP protocol-envelope delivery for all three streams; it does not claim Alibaba Cloud authentication or ingestion acceptance. No cloud SLS test environment was available. Compatibility tests intentionally retain business Jackson serialization and reject generated reference aliases. Production cloud deployment is not performed by this release verification.

## MR/PR Links

Internal MR and GitHub upstream PR are pending. Their real URLs and merge SHAs will be recorded in release evidence after creation; this file does not represent a published or merged release.
