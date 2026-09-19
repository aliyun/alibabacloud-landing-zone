# MCP Skill Package Upload

AutoWonder MCP supports uploading complete Skill packages and creating or updating Skill records from the uploaded package reference.

## Supported package formats

- `.zip`
- `.tar.gz`
- Directory contents via `files` (relative path → base64 file content), packed into ZIP by the server.

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

### Directory input (inspect and upload)

Both tools also accept a directory without requiring the caller to create a ZIP:

```json
{
  "files": {
    "SKILL.md": "<base64 SKILL.md bytes>",
    "scripts/run.sh": "<base64 script bytes>",
    "assets/icon.png": "<base64 image bytes>"
  },
  "type": "SKILL"
}
```

Send paths relative to the selected directory, without its enclosing folder name. MCP cannot read a directory path on the caller's machine: the caller reads the files and supplies their contents. Binary and dot-prefixed files are preserved; empty files use an empty base64 string. Empty directories are omitted. For upload, `files` works for `SKILL`, `PLUGIN`, and `HOOK`; inspect reads Skill metadata from root `SKILL.md`.

Choose exactly one of `files` or `contentBase64`. Archive input requires `fileName`; directory input defaults to `directory.zip` and only permits `.zip` if `fileName` is supplied. Both the archive and total decoded contents are limited to 100 MB, with at most 500 entries. The generated ZIP has stable ordering and timestamps. `expectedMd5`, if supplied for directory input, checks the generated ZIP; use the returned `packageMd5` for subsequent create/update calls.

The web capability form likewise accepts either a folder or a ZIP for creation and replacement. Skill metadata is read by the same server inspect endpoint. ZIP files must already have `SKILL.md` (or `hook.yaml` for Hooks) at the archive root.

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
- Invalid archive or directory contents, unsupported extension, unsafe path, missing `SKILL.md`, package size/entry limits, digest mismatch: `PARAM_INVALID`
- Duplicate Skill name without a matching idempotent package: `SKILL_DUPLICATE_NAME`
- Missing Skill on update/query: `SKILL_NOT_FOUND`
- Concurrent version conflict: `SKILL_VERSION_CONFLICT`
