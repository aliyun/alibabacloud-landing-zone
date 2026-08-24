# Community Documentation Policy

## Purpose

Keep only documentation required to deploy, operate, verify, synchronize, or
extend the community edition. Product-development working notes belong in Git
history, not in the published community tree.

## Retained Documents

- `docs/autowonder-schema.sql`: complete schema for a fresh installation.
- `docs/migration/`: immutable, versioned SQL for incremental upgrades.
- `docs/autowonder-community-templates.sql`: idempotent system squad-template seed.
- `docs/THIRD-PARTY-NOTICES.md`: required third-party notices.
- `docs/openapi-reference.md`: public server API reference.
- `docs/scheduler-executor-protocol.md`: server/client runtime protocol.
- `docs/mcp-memory-management.md`: MCP memory tools.
- `docs/mcp-skill-package-upload.md`: MCP Skill package tools.
- `docs/autowonder-s3-storage.md`: optional standard S3 backend configuration.
- `docs/community/README.md`: community runtime entry point.
- `docs/community/application.env.example`: runtime configuration inventory.
- `docs/community/docker-compose.dependencies.yml`: local dependencies.
- `docs/community/verification.md`: release verification gates.
- `docs/community/upstream-sync-guide.md`: master-to-community sync rules.
- `docs/community/upstream-sync-log.md`: verified upstream baselines and history.
- `docs/community/docs-policy.md`: this retention policy.
- `docs/community/squad-template-seed.md`: template data and deployment contract.
- `docs/skills/`: published product Skills (e.g. execution optimizer); sync from
  master as-is.

The deployment Skill under `skills/deploying-autowonder-on-alibaba-cloud/` owns
the detailed Alibaba Cloud deployment, operations, troubleshooting, acceptance,
rollback, and teardown documentation.

## Excluded Documents

Do not publish these categories in `community`:

- Superpowers specs, implementation plans, and other development working notes;
- completed fix reports, rehearsals, execution journals, and temporary runbooks;
- stale PRDs, gap analyses, backlogs, and internal deployment instructions;
- documents containing internal endpoints, credentials, environment identities,
  or obsolete KeyCenter/Akless instructions.

Database migrations are retained only under `docs/migration/` and must follow
its immutable naming and execution contract. Temporary migration notes, database
dumps, rollback experiments, and environment-specific SQL remain excluded.

## Upstream Sync Rule

When merging `origin/master`, apply
[upstream-sync-guide.md](upstream-sync-guide.md) first, then apply this policy to
new or changed documentation. Keep master product behavior, but do not restore
excluded documentation merely because it exists on master.

Any proposed exception must be necessary for community deployment, operations,
verification, synchronization, or supported extension, and must not expose
Alibaba-internal implementation details.
