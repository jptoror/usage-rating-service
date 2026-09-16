---
description: Review the current changes for correctness, financial safety, tenant isolation and architecture conformance
---

Review the changes in this repository using the `pr-review` skill.

Scope: $ARGUMENTS (if empty, review uncommitted changes plus commits ahead of `main`)

Start by gathering the diff:

!`git status --short`
!`git diff main...HEAD --stat 2>/dev/null || git diff HEAD --stat`

Then work through the `pr-review` checklist in order. Report findings ordered by
severity with `file:line` references, separating blocking issues from preferences.
If the diff is clean, say so plainly rather than manufacturing findings.
