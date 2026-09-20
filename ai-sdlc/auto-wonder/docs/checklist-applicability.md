# Conditional checklist completion

A step may exit through a branch where some checks do not apply. Keep unconditional checks mandatory; grant an exception explicitly in the SDLC definition:

```json
[
  {"id":"scope","text":"Record actual scope and outcome"},
  {
    "id":"code-tests",
    "text":"Related code tests pass",
    "allowNotApplicable":true,
    "notApplicableWhen":"The task exits through clarification without code changes"
  }
]
```

With an upgraded runtime, the agent reports:

```json
{
  "stepId":"review",
  "stepAttempt":1,
  "type":"checklist_updated",
  "checklist":[
    {"id":"scope","checked":true},
    {
      "id":"code-tests",
      "checked":false,
      "status":"NOT_APPLICABLE",
      "reason":"Clarification branch; no code changes. See evidence/scope.md."
    }
  ]
}
```

`checklistRequired=true` still rejects empty or pending checklists. `NOT_APPLICABLE` is accepted only for a definition that explicitly opts in, includes a condition, and has a nonblank execution reason. Reporting both `checked=true` and `NOT_APPLICABLE` is invalid. Definition ids, text, and exception permissions are frozen; an agent cannot omit required checks or override these permissions in its update. Partial updates retain the other configured checks.

The runtime checks the structured permission and reason. The agent must establish that the actual branch meets the configured condition and cite evidence; this is not a general-purpose language interpreter for arbitrary predicates. Do not opt unconditional release/security checks into this mechanism.

Execution events, progress and runtime-result retain the status and reason. An exception is never represented as a passed test. A completed runtime step still does not by itself mean the requested business outcome was delivered: blocked handoffs must state the unmet outcome and recovery conditions explicitly.

## Rollout

1. Deploy the matching `auto-wonder-client-runtime` change before configuring conditional checks. Older runtimes only understand `checked` and will not accept this completion protocol.
2. Deploy platform definition validation and authoring help, then add explicit conditions where needed.
3. Existing string checklists and `{id,text,checked}` updates remain supported. New dispatch packages reset execution results; they preserve the applicability definition.

Until runtime rollout, use genuinely universal process checks such as scope confirmed, actual outcome recorded, and evidence/handoff submitted. A blocked run must satisfy these obligations truthfully; never mark a branch-specific test as passed merely to close the run.

For workitem 16887, workspace 10001 now has separate workflows: 10018 for completed-workitem distillation and 10019 for Terraform memory fact review. Both currently use compatible universal checks. No historical run is restarted or relabeled by this code change.
