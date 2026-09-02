# Community Verification

Verification date: 2026-09-02 (release v0.7.0, master baseline `25371cb1`)

## Completed Evidence

| Check | Result | Evidence |
| --- | --- | --- |
| Backend package | PASS | `mvn -DskipGitCommitId=true clean verify`: BUILD SUCCESS, 2652 tests, 0 failures, 0 errors, 1 skipped |
| Frontend install | PASS | Public npm lockfile; `npm ci --ignore-scripts` clean |
| Frontend tests | PASS | 116 files, 901 tests passed, 1 skipped |
| Frontend lint | PASS | ESLint completed with 0 errors and 3 existing hook warnings |
| Frontend production build | PASS | `tsc -b && vite build` succeeded |
| Community boundary tests | PASS | `CommunityBuildInputTest` and `CommunityDependencyBoundaryTest` pass; dependency tree free of KeyCenter, Normandy, AkLess, RASS, BUC and `log4j:log4j`; internal-reference scan returns nothing |
| Database schema | PASS | Fresh MySQL 8 import of `docs/autowonder-schema.sql` produced 64 tables, including `scheduled_task`, `scheduled_task_run`, `workspace_access_request` and the `normalized_idempotency_key` STORED generated column |
| Local startup smoke | PASS | Jar started against fresh MySQL 8 and Redis; `V037 schema capability: mode=V037_READY, mapper_mode=SOURCE_AWARE, scheduled_available=true`; `/checkpreload.htm` HTTP 200; `aoneEnabled: false`; branding reported `communityEdition: true` and runtime `0.2.150`; register → login → create workspace → switch → `/api/capabilities/scheduled-task` (`available:true`) → `/api/workspaces/all` discovery all succeeded; zero ERROR lines in the log |
| Deployment Skill tests | PARTIAL | 70 passed, 31 failed. The failures are byte-identical on the previous community tip `51ff353d` and are stale assertions against script content that moved into `scripts/internal/release-transfer.sh`. Open work, not a regression of this release |
| Upgrade Skill tests | PARTIAL | 66 passed, 6 failed, 1 skipped; same pre-existing status as above |
| Linux image build | NOT RE-RUN | Last measured 2026-08-04: `docker build --platform linux/amd64` passed 1671 container tests, image `sha256:3f44d9514a0f4e7a7b8add0f373d16c2c04d6bd8a4ffb5a03bd07ef5f3a1b055`. Re-run before publishing a release image |
| Runtime image identity | NOT RE-RUN | Last measured 2026-08-04: Linux amd64, configured user `autowonder`, JAR present |
| Runtime base image | NOT RE-RUN | Last measured 2026-08-04: Temurin amd64 manifest digest `sha256:468586c92d39f8cbad76574623db3fe001625ed4d895431c3ff7bd2ec9ce7ae3` |

The startup smoke run supplied syntactically valid placeholder OSS settings to
exercise application initialization only. It did not claim an OSS network or
credential check. Its temporary MySQL and Redis containers, data volume and
generated secrets were removed immediately after verification.

The frontend toolchain and React Router were upgraded after review. The current
audit reports two high entries for one React Router RSC-mode CSRF advisory
(`react-router` and `react-router-dom`). AutoWonder uses SPA routing and does not
enable React Server Components or framework actions, so the affected path is not
active. npm proposes `7.11.0`, but auditing that exact version reports other high
React Router advisories; current `7.18.2` leaves only the non-applicable RSC-mode
finding. Keep this advisory tracked for the next fixed release.

## Automated Gates

```bash
mvn -DskipGitCommitId=true clean verify
cd frontend
npm ci --ignore-scripts
npm test -- --run
npm run build

cd ..
mvn -DskipFrontend=true -DskipGitCommitId=true dependency:tree \
  > target/community-dependency-tree.txt
! rg -i 'keycenter|normandy|akless|rass|(^|[[:space:]])log4j:log4j:' \
  target/community-dependency-tree.txt
! rg -i 'alibaba-inc\.com|aliyun-inc\.com|daily-keycenter\.alibaba\.net' \
  pom.xml frontend/package-lock.json frontend/.npmrc APP-META src/main src/main/resources
```

## External Service Acceptance

Use non-production test credentials and sanitize all captured output.

1. Start the image with disposable MySQL/Redis plus real OSS credentials.
2. Verify `/checkpreload.htm` and `/api/integrations/capabilities` return HTTP 200.
3. Exercise OSS upload/read through `OSS_ENDPOINT`, then verify a presigned URL
   uses the `OSS_PUBLIC_ENDPOINT` HTTPS host and is downloadable outside the VPC.
4. Create and read one secret system setting or IM credential. Query its `credential_ref`;
   assert it starts with `enc:v1:` and does not contain the submitted plaintext.
5. With SLS enabled, emit a system log, business log, and metric; confirm each
   arrives in its configured logstore.
6. Start a second instance with `AUTOWONDER_SIGAR_ENABLED=false`; health must
   remain successful and SIGAR gauges must be absent.

Real OSS/SLS credentials were not available in the local environment during the
automated run, so the upload/read/presign and SLS delivery checks remain release
environment acceptance items rather than being reported as passed.

The local startup smoke supplied syntactically valid placeholder OSS settings to
exercise application initialization only. It did not claim an OSS network or
credential check. Its temporary database, database user, and Redis container were
removed immediately after verification.
