# MCP Skill Package Upload

AutoWonder MCP supports uploading complete Skill packages and creating or updating Skill records from the uploaded package reference.

## Supported package formats

- `.zip`
- `.tar.gz`

Packages must use safe relative paths. Root `SKILL.md` is required for `SKILL` packages. Directories such as `references/`, `scripts/`, `assets/`, and dot-prefixed files are preserved when the archive is stored. Absolute paths, backslashes, `..` traversal, symlink entries in tar archives, over-large archives, too many entries, inflated-size overflow, and digest mismatches are rejected.

## MCP tools

### `autowonder.inspect_skill_package`

Input:

```json
{
  "fileName": "demo-skill.zip",
  "contentBase64": "<base64 package bytes>"
}
```

Output includes `name`, `description`, `fileName`, and `packageSize`.

### `autowonder.upload_skill_package`

Input:

```json
{
  "fileName": "demo-skill.zip",
  "contentBase64": "<base64 package bytes>",
  "type": "SKILL",
  "expectedMd5": "<optional md5>"
}
```

Output includes `packageOssRef`, `fileName`, `packageSize`, `packageMd5`, `packageSha256`, `type`, `name`, and `description`.

### `autowonder.create_skill_from_package`

Input:

```json
{
  "packageOssRef": "<packageOssRef from upload_skill_package>",
  "type": "SKILL",
  "expectedMd5": "<optional md5>",
  "idempotencyKey": "demo-skill-v1"
}
```

Output is the created Skill record, including package metadata.

### `autowonder.update_skill_package`

Input:

```json
{
  "id": 10001,
  "packageOssRef": "<packageOssRef from upload_skill_package>",
  "expectedMd5": "<optional md5>"
}
```

Output is the updated Skill record, including the new package metadata and version.

## End-to-end MCP example

```json
{"name":"autowonder.upload_skill_package","arguments":{"fileName":"demo-skill.zip","contentBase64":"<base64 package bytes>","type":"SKILL","expectedMd5":"<md5>"}}
```

Use the returned `packageOssRef`:

```json
{"name":"autowonder.create_skill_from_package","arguments":{"packageOssRef":"<returned packageOssRef>","type":"SKILL","expectedMd5":"<md5>","idempotencyKey":"demo-skill-v1"}}
```

Then query the created Skill:

```json
{"name":"autowonder.get_skill","arguments":{"id":10001}}
```

## Error behavior

- Missing or malformed MCP arguments: `MCP_TOOL_ARGUMENT_INVALID`
- Invalid archive, unsupported extension, unsafe path, missing `SKILL.md`, package size/entry limits, digest mismatch: `PARAM_INVALID`
- Duplicate Skill name without a matching idempotent package: `SKILL_DUPLICATE_NAME`
- Missing Skill on update/query: `SKILL_NOT_FOUND`
- Concurrent version conflict: `SKILL_VERSION_CONFLICT`
