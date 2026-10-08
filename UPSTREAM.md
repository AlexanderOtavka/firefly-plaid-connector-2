# Relationship to upstream

- Upstream: <https://github.com/dvankley/firefly-plaid-connector-2>
- Forked from: release `v1.5.1`, commit `33421d47ffb476cc2a2f75f922549cfb19965e1d`
- License: GNU General Public License v3.0; see `LICENSE`

This fork is maintained independently. Upstream changes are merged in deliberately, by
merging an upstream release tag and resolving conflicts against the local changes listed in
`LOCAL_CHANGES.md`, rather than tracked automatically. Nothing is being upstreamed for now.

`src/manage/` and `src/manageTest/` (the management dashboard) have no upstream
counterpart, so they rarely conflict; the small hooks they need in upstream files are listed
in `LOCAL_CHANGES.md`. Upstream's `.github/` workflows and Dependabot config are replaced by
this fork's Nix build and CI (`flake.nix`, `nix/`, `.github/workflows/`).
