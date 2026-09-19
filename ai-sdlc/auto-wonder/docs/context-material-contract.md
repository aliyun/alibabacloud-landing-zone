# Context material identity and incremental comment access

This change is implemented in the Java server. It does not change SDLC steps,
handoff routing, model selection, or the provider's message history.

## Stable adopted-memory paths

The assembler previously assigned `mem_0`, `mem_1`, etc. according to the DAO's
unspecified result order. Reordering bindings could change the meaning of a path;
duplicate bindings could repeat a memory and consume the 50-item limit.

New packages use `memory/mem_id_<memoryId>.md`, order references by numeric memory
ID, and count at most 50 distinct, valid, adopted memories. The original Markdown
is preserved. Content changes remain visible in the manifest's existing file
digests. The `mem_id_` namespace intentionally cannot collide with legacy
positional `mem_<index>` paths.

This also affects conversation capability packages that use the shared memory
assembler. Existing immutable packages/checkpoints are not rewritten. Consumers
must discover memory paths from the current package rather than construct ordinal
names. When resuming an old session, stale positional references must be resolved
against its original package, not translated to IDs in the new package. Runtime
compatibility with file discovery must be checked before rollout.

## Optional comment index

When per-comment snapshots are supplied, the existing v1 task manifest adds:

```json
{"commentIndex": "context/comments-index.json"}
```

The index is versioned separately:

```json
{
  "schemaVersion": "autowonder.commentIndex.v1",
  "comments": [
    {
      "id": "10",
      "authorType": "HUMAN",
      "authorRef": "7",
      "path": "context/comments/10.md",
      "sha256": "sha256:<digest-of-the-file-bytes>",
      "sizeBytes": 42
    }
  ]
}
```

IDs are strings to preserve 64-bit precision. Files contain exact UTF-8 comment
content, including whitespace and code fences; a null body becomes an empty file.
The index and comment files are covered by `manifest.fileDigests` and the existing
archive checksum/signature. Duplicate or non-positive snapshot IDs fail package
creation rather than silently overwrite a requirement.

The assembler fetches comments once, applies tenant filtering, and derives both
the index snapshots and the complete legacy `comments.md` from that same list.
Existing clients can keep reading `comments.md`. Legacy callers that only supply
`commentsMd`, and packages without comments, do not gain an index field or file.

## Runtime consumption (follow-up in the Go repository)

1. Read the optional index; use the legacy file if unsupported or absent.
2. On a fresh session, load all required comments. Never skip a new user constraint
   based only on an inferred relevance score.
3. On a genuinely resumed session, compare IDs, content hashes and author metadata
   against the material actually injected into that session. Load changed/new
   entries; do not inject both indexed copies and the aggregate legacy file.
4. Reset or reconcile this receipt state after compaction, recovery or a new
   provider session. Package presence alone does not prove the model read a file.

These additions make individual comments addressable but do not themselves
reduce model input tokens. They increase archive size by retaining legacy content
alongside the new files during compatibility rollout. `sizeBytes` describes
storage bytes, not tokenizer counts or realized billing savings.

## Verification

Unit tests cover stable memory identity/order, duplicate bindings, the 50-memory
limit, tenant filtering, exact comment content, unchanged hashes after append,
changed hashes after edit, invalid snapshots and legacy package compatibility.
The existing signed server/Runtime contract fixture remains unchanged.

```sh
mvn -DskipFrontend=true -DskipGitCommitId=true \
  -Dtest='PackageContextMemoryTest,PackageContextAssemblerTest,TaskPackager*Test' test
```
