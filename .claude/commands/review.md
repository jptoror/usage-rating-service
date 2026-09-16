---
description: Review the current changes for correctness, financial safety, tenant isolation and architecture conformance
---

Review the changes in this repository using the `pr-review` skill.

Scope: $ARGUMENTS (if empty, review whatever is uncommitted, else the commits ahead of `main`)

Gather the diff. These cover the three situations this is invoked in — mid-change on a
branch, on a finished branch, and on `main` with uncommitted work — because
`git diff main...HEAD` returns nothing at all when you are on `main`, which silently
looks like "no findings":

!`git status --short`
!`git diff --stat HEAD 2>/dev/null | tail -5`
!`git log --oneline main..HEAD 2>/dev/null | head -10 || echo "(on main, or no commits ahead)"`

Then read the actual changes:

- Uncommitted work: `git diff HEAD`
- A branch's commits: `git diff main...HEAD`
- Nothing uncommitted and nothing ahead of `main`: review the most recent commit with
  `git show HEAD`, and say that is what you reviewed.

Work through the `pr-review` checklist in order. Report findings ordered by severity with
`file:line` references, separating what blocks a merge from what is a preference.

If the diff is clean, say so plainly — a review with no findings is a valid outcome, and
padding it with style notes wastes the reader's attention on the one occasion it matters.
