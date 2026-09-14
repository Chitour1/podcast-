# Build Status

## Completed
- Expo/React Native Android and iOS codebase.
- SQLite local-first schema and migrations.
- English/Arabic copy and RTL-aware layouts.
- kg/lb display conversion.
- 40+ exercise seed library with original offline illustrations.
- equipment presets and custom exercise creation.
- onboarding inputs required by product brief.
- deterministic 2–6 day routine templates.
- manual routine builder, day duplication, reorder controls, set/rep/rest/priority editing.
- workout logger with autosave, resume, set types, RIR, previous performance, rest timer and manual overrides.
- deterministic progression + Why explanation persistence.
- Busy single-exercise substitution with today/permanent scope.
- Time Compression that protects high-priority work and excludes removed work from progression failure.
- workout summary, history detail, progress/e1RM charts and PRs.
- Hevy/Strong/generic CSV import preview, fuzzy matching, duplicate detection and local import.
- CSV/JSON export.
- light/dark/system appearance.
- domain tests for progression, Busy, Time Compression and units.
- standalone Android release CI and EAS profiles.

## In progress
- Supabase account/sync hardening.
- RevenueCat live offerings/paywall.
- notification scheduling for background rest timers and optional reminders.
- gesture-based drag reordering (button reordering works today).
- full accessibility audit and physical-device visual QA.

## Blocked on product-owner external accounts
- Supabase project URL/anon key and applying the RLS migration.
- RevenueCat project/API keys plus App Store/Google Play products.
- Apple Developer / Google Play signing and store agreements.

## Next
- Configure external services, run sync and sandbox billing E2E, then production EAS builds and store-readiness QA.
