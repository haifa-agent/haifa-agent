# Haifa Agent Skill Core

Safe Agent Skills `SKILL.md` parsing, bounded package indexing, deterministic SHA-256 identities,
Classpath/local/in-memory sources, scoped discovery, collision diagnostics, immutable effective catalogs,
and exact content loading.

This module does not depend on Context or Runtime, execute scripts, access credentials, call networks, or
trust package metadata as authorization. Local roots are supplied by a trusted application boundary.

## Reviewed package eligibility

Script-bearing packages remain `REVIEW_REQUIRED` unless `SkillCatalogBuilder` receives an active, unexpired,
subject-matching `SkillPackageReviewGrant` whose coordinate, registration digest, and complete package index
digest all match. The resulting `FrozenSkillBinding` records the accepted package-grant reference. A script
execution grant does not affect catalog eligibility, and this module still never executes package resources.
