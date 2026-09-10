# SimpleController Stable Recovery Baseline

SimpleController is a critical accessibility application for Tyler. Treat its confirmed working state as a protected recovery point.

## Confirmed baseline

- Repository: `https://github.com/Colonelwheel/Simplecontroller.git`
- Branch at confirmation: `master`
- Stable commit: `c6b60428296e1bc2a93fe3859bb962104f769bc9`
- Short commit: `c6b6042`
- Recovery tag: `stable-2026-09-10`
- Confirmed by Tyler on September 10, 2026.

Tyler confirmed that the GitHub repository state at this commit is a stable copy. Here, "stable" means a user-confirmed working recovery snapshot; it does not claim that exhaustive formal testing proved the absence of every defect.

## Preservation rules

- Preserve the Git history containing this commit.
- Preserve the annotated `stable-2026-09-10` tag and keep it pointing to the confirmed commit.
- Never force-push, rewrite, squash away, delete, or overwrite this recovery point.
- Base risky or substantial work on a separate branch or worktree when practical.
- Keep later changes focused and reversible, with verification proportional to their risk.
- If a regression cannot be corrected safely, use this commit as the known recovery reference.
- Documentation-only commits made after this baseline do not change which application state Tyler confirmed as stable.

## Local checkout distinction

At the time this baseline was recorded, the main local checkout contained substantial uncommitted and divergent work. Those local changes are important and must not be discarded, but they are not included in the confirmed GitHub baseline unless Tyler deliberately commits and confirms them later.

Before changing the local checkout, inspect Git status and preserve all pre-existing work. Do not reset, clean, stash, rebase, pull over, or overwrite it without Tyler's explicit approval.
