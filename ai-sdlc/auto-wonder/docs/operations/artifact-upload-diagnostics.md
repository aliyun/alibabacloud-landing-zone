# Artifact upload response diagnostics

These INFO logs apply only to `POST /api/daemon/dispatches/{id}/artifacts`.
No executor update or logging-level change is required. Deploy the server branch
to every replica before reproducing an upload recovery.

Search by the request path (for example `/api/daemon/dispatches/13616/artifacts`)
and group by `request_id` / `requestId`; a dispatch can issue multiple uploads.

1. `artifact upload http entered`: the request reached the application filter.
   `requestBytes` is the declared Content-Length, not an independently measured
   request size; `-1` means unknown. The query string is not logged.
2. `artifact upload receipt prepared`: the controller prepared its receipt,
   including accepted/rejected counts. This is **before** HTTP serialization.
3. `artifact upload http completed`: status, content type/encoding, observed
   output-stream byte count, first non-JSON-whitespace byte (decimal), exception
   class and duration at the application filter boundary.

Useful byte values: `68` = `D`, `123` = `{`, `91` = `[`, `60` = `<`, `-1` = none
observed. A `{` prefix does **not** prove the entire body is valid JSON.
No response body, file content, token, or storage reference is retained/logged by
this probe. It adds no response buffering, parsing, forced flush or retry.

Interpretation limits:

- `writerUsed=true`: the servlet writer was used, so stream byte statistics do
  not describe the writer's output. The writer is deliberately not replaced.
- `containerResponse=true`: sendError/sendRedirect delegated rendering to the
  container; discarded application bytes are excluded. The eventual container
  body is not observed by this probe.
- `asyncStarted=true`: this record is not an asynchronous completion guarantee.
- A thrown exception can leave status 200 at this boundary; inspect
  `exceptionType`, not status alone. Partially failed writes cannot be counted
  precisely.
- The probe observes application output, not bytes received by the executor.
  Compression or downstream proxies may change the representation. No entry log
  in the available search results alone proves neither a proxy block nor that
  every server replica was searched.

For a reproduction, collect all three markers and existing controller/auth logs
for each request ID, plus executor timestamps/version and the recovery error.
Do not replay business execution solely to collect these logs; use the executor's
existing pending-upload recovery when applicable.
