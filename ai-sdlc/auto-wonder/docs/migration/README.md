# Database Migration Contract

This directory contains incremental database changes for upgrades. A fresh
installation still imports `docs/autowonder-schema.sql`; an upgrade never imports
that full schema.

Migration files use this exact form:

```text
V<n>__<description>.sql
```

`V` is uppercase, `<n>` is a unique positive integer, and versions are strictly
increasing. Use lowercase ASCII words separated by underscores for the
description. After a migration reaches a published branch, never modify, rename,
or delete it. Add a higher version for every correction.

The deployment Skill compares the active and target commits, executes only new
versions in numeric order after explicit approval, and records their checksums in
`autowonder_schema_history`. Do not place a full-schema snapshot, rollback SQL,
credentials, environment-specific identifiers, or Alibaba-internal endpoints in
this directory.
