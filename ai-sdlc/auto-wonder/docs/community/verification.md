# Community Verification

Verification date: 2026-08-04

## Completed Evidence

| Check | Result | Evidence |
| --- | --- | --- |
| Backend package | PASS | Host backend verify: 1672 tests, 0 failures, 0 errors |
| Frontend install | PASS | Public npm lockfile; 690 packages audited |
| Frontend tests | PASS | 73 files and 445 tests passed |
| Frontend lint | PASS | ESLint completed with 0 errors and 2 existing hook warnings |
| Frontend production build | PASS | Vite 6.4.3 built 4754 modules |
| Community boundary tests | PASS | 10 focused build, dependency, access, and runtime boundary tests passed |
| Linux image build | PASS | `docker build --platform linux/amd64`: container Maven build passed all 1671 tests; image `sha256:3f44d9514a0f4e7a7b8add0f373d16c2c04d6bd8a4ffb5a03bd07ef5f3a1b055` |
| Runtime image identity | PASS | Linux amd64, configured user `autowonder`, JAR present |
| Runtime base image | PASS | Temurin amd64 manifest digest `sha256:468586c92d39f8cbad76574623db3fe001625ed4d895431c3ff7bd2ec9ce7ae3` |
| Local startup smoke | PASS | Image started as `autowonder` with fresh MySQL 8 and passwordless Redis; preload and public capabilities returned HTTP 200, with `aoneEnabled: false` |

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
3. Exercise one OSS upload, read, and presigned-download workflow.
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
