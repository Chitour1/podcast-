# Program Smarter

**Your Program. Smarter.** A local-first strength-training application for iOS and Android.

## Architecture
- React Native + Expo + TypeScript.
- SQLite is the source of truth during workouts.
- Deterministic double-progression engine in `src/domain/progression.js`.
- Original local SVG exercise illustrations in `src/artwork.tsx` (no copyrighted media).
- Import/export happens locally on-device.
- Cloud and subscription adapters are configured separately; the workout core never depends on them.

## Core flows implemented
- Full onboarding: experience, goal, 2–6 training days, session duration, equipment, units, program choice and RIR familiarity.
- Deterministic program templates from 2 to 6 days/week.
- Manual routine builder, day duplication, exercise reordering, priorities and exercise library/custom exercises.
- Offline workout logging with immediate SQLite persistence, rest timer, set types, RIR, previous performance, Why, Busy substitutes and Time Compression.
- Workout summary, history details, progress charts and Epley e1RM PRs.
- Hevy/Strong/generic CSV local import with preview, duplicate detection and fuzzy exercise matching.
- CSV and JSON export.
- English and Arabic UI, kg/lb display conversion, system/light/dark themes.

## Development
```bash
npm install --legacy-peer-deps
npx expo install expo-sqlite expo-status-bar react-native-safe-area-context expo-linear-gradient expo-haptics expo-document-picker expo-file-system expo-sharing react-native-svg
npm run test:domain
npx expo start
```

## Android release APK
The repository CI prebuilds native Android and runs Gradle `assembleRelease`, then uploads an installable standalone APK as a GitHub Actions artifact.

## Production store builds
`eas.json` contains development, preview and production profiles. Store submission is intentionally not automatic.
