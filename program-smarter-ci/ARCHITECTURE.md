# Architecture

## Local-first
All active-workout writes go directly to SQLite. The app never requires a network response to record a set. Active sessions and rest-timer end timestamps survive process restarts.

## Domain model
Local schema contains settings, equipment profiles, exercises, routines, routine days/exercises, workout sessions/exercises/sets, progression configs/decisions, substitutions, imports, sync queue and entitlement metadata.

## Progression
The engine is deterministic and testable. Valid work sets are the main input. Busy substitutions and Time Compression omissions are excluded from failure signals. Two high-quality top-range exposures earn the minimum load increment; a single poor exposure does not cause a reduction; repeated misses may produce a small optional reduction. Long gaps may create an optional conservative return adjustment.

## Exercise artwork
Artwork is original vector illustration code stored locally. It is grouped by movement pattern and renders without network access.

## Sync boundary
The local database remains authoritative during training. Cloud synchronization is designed as an optional boundary so network failures cannot block logging.
