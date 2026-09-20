# Runtime-authoritative dispatch recovery

## Why this change exists

The previous server treated any unresolved stop as an executor-wide quarantine. One stale dispatch could
therefore prevent an executor from using its remaining configured slots, while the UI eventually mislabeled the
symptom as a queue deadline. Recovery also used `runningDispatchIds`, which cannot represent assignments
being recovered before they acquire a slot.

The new contract makes the current Runtime session's complete `ownedDispatchIds` inventory authoritative:

- scheduling counts the union of persisted occupying dispatches and Runtime running dispatches, plus
  conversation turns;
- one stop-pending dispatch consumes one slot but never disables the whole executor;
- absence can repair only stop/pause handshakes, after a grace period and session/readiness checks;
- ordinary ACKED/RUNNING work is never made terminal merely because it is absent;
- startup recovery and inventory overflow fail closed;
- retryable capacity waits remain PENDING; deterministic release/configuration errors fail immediately;
- a failed row without `executor_id` is shown as “未派发到客户端”.

The existing `HEARTBEAT` remains backward compatible. A Runtime that advertises
`dispatch_inventory_v1` supplies authoritative ownership and enables lost stop/pause reconciliation.
A Runtime without that feature still refreshes presence and schedules from its reported capacity and
running IDs; the server never rejects or disconnects it merely for lacking the feature. Legacy reports
are not treated as proof that a missing dispatch has stopped.
For conversation activity, a missing legacy heartbeat field remains unknown; only an explicitly
reported empty list means idle. This distinction is preserved in the internal Redis snapshot without
adding any heartbeat fields.

Heartbeat persistence adds no Redis Lua or cross-key atomic operation, and requires no cluster hash-slot
layout. Session ownership is written only when a connection is authenticated; heartbeats cannot write an
older session back. Scheduling capacity, ownership and protocol capabilities are read from one
session-bound snapshot. A graceful disconnect marks only that session closed, so it becomes unavailable
immediately without deleting a replacement session's presence; liveness data still has bounded TTLs.

## Deployment order

1. Apply `docs/migration/V070__dispatch_executor_status_index.sql` to add the executor/status
   lookup index used by slot accounting.
2. The server and Runtime may be deployed in either order; old and new heartbeat senders are accepted.
3. For Runtime versions containing `dispatch_inventory_v1`, confirm heartbeats report `dispatchInventoryReady=true`, no
   `dispatchInventoryError`, the configured effective capacity, and the expected owned IDs.
4. Confirm recovered dispatch 13327 remains owned/running until it stops; stale 13311/13320 may disappear
   only when Runtime no longer owns them.
5. Deploy the server normally. A maintenance window or fleet-wide atomic client upgrade is not required.
6. Verify executor 10067 is selectable and capacity equals the distinct occupied dispatch IDs plus active
   conversation turns, not an executor-wide quarantine flag.
7. Retry the failed attempts for workitems 55393 and 55394 from the product UI. A previously terminal
   `QUEUE_DEADLINE_EXCEEDED` row is historical and is not silently rewritten. Check workitem 55376 after
   inventory reconciliation and retry only if its latest attempt is already terminal.

The permanent fix begins recovering on the first complete heartbeat after both sides are deployed; no SQL
or Redis hotfix is required. Existing terminal attempts still require the explicit product-level retry in
step 7 so audit history remains truthful.

## Verification and rollback

Verify heartbeat acceptance, executor capacity, successful dispatch ACK, pause/recovery, and that one stale
stop does not block a second dispatch. Alerts should distinguish incompatible/missing inventory, inventory
not ready, capacity exhausted, and deterministic configuration failure.

Server and Runtime rollback order is unrestricted because both heartbeat forms remain accepted. No
production data mutation is part of rollback.

Stop reconciliation reads MySQL `DATETIME` with JDBC `getTimestamp`, rather than casting the
driver-dependent result of `getObject`. Regression coverage includes the `LocalDateTime` mapping
returned by Connector/J, for both periodic and heartbeat-triggered reconciliation.

Heartbeat reconciliation isolates stop repair, pause repair, waiter repair and capacity wake failures.
An error in one repair does not suppress scheduling other queued tasks; session validity is rechecked
before capacity wake and drain. No new scheduler, Redis operation or heartbeat field is required.

Runtime recovery workers that exit without executing no longer retain in-memory ownership. Failed
upload/publication workers also release their local ownership on exit, preserving their persisted
materials and pending results. This enables the existing complete-inventory stop repair; it does not
fabricate task success/failure, delete execution history, or release an execution still running.

The client pause fix bounds the safe-tool wait to 30 seconds and then uses existing provider shutdown.
It waits for actual execution shutdown before reporting a checkpoint. Server deployment alone cannot
change an older client's local pause/recovery behavior; upgrading Runtime enables this local repair,
while legacy clients continue to connect and schedule normally.
