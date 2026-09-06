# Contributing

## Branch & PR workflow

All changes land via a branch + pull request into `main` — no direct commits to `main`.

Branch naming:

| Prefix | For |
|--------|-----|
| `feature/<slug>` | New functionality |
| `fix/<slug>` | Bug fixes |
| `docs/<slug>` | Documentation-only changes |
| `chore/<slug>` | Housekeeping, deps, CI, tooling |

Example: `feature/manual-provisioning-ui`, `fix/ota-checksum-mismatch`, `docs/api-reference`.

Open a PR against `main` when the branch is ready for review. Keep PRs scoped to one
branch-worthy change — split unrelated work into separate PRs.

> **Note:** branch protection on `main` (require a PR before merge, disallow force-push) should
> be turned on from the repo's GitHub Settings → Branches. This isn't automated yet — do it once
> after the first push.

## Secrets

This repo is public. Never commit:
- `provisioned.creds` (AWS IoT device certificate/private key) — already gitignored.
- Any real AWS IoT endpoint, account ID, or other environment-identifying value — set these via
  environment variables or a local, untracked `application.properties`/`application-local.properties`
  instead. See `README.md` → Configuration.
- `*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.jks`, `.env` files — already gitignored.

If you're ever unsure whether something is sensitive, ask before committing it.
