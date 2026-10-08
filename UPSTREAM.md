# Vendored upstream

- Repository: <https://github.com/dvankley/firefly-plaid-connector-2>
- Release: `v1.5.1`
- Commit: `33421d47ffb476cc2a2f75f922549cfb19965e1d`
- Vendored: 2026-08-17
- License: GNU General Public License v3.0; see `LICENSE`

The upstream `.github` and `.junie` directories are intentionally excluded.
This repository owns the CI workflow that builds the vendored source.
See `LOCAL_CHANGES.md` for modifications made after vendoring.

To update the vendor tree, replace its upstream files from a pinned release
commit, preserve this file and `LOCAL_CHANGES.md`, and update the release and
commit above. Review the upstream diff before committing the replacement and
reapply each documented local change.

`src/manage/` and `src/manageTest/` (the management dashboard) are entirely
local and have no upstream counterpart: keep them as they are when replacing
upstream files, then reapply the small hooks they depend on in upstream files,
listed in `LOCAL_CHANGES.md`. The dashboard is a much larger delta than the
other local changes; it is not intended for upstreaming as it stands.
