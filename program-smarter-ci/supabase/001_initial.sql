create extension if not exists pgcrypto;

create table if not exists public.user_profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  experience text not null default 'intermediate',
  goal text not null default 'balanced',
  units text not null default 'kg',
  lang text not null default 'en',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
create table if not exists public.routines (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  is_active boolean not null default true,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  deleted_at timestamptz
);
create table if not exists public.routine_days (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  routine_id uuid not null,
  name text not null,
  position integer not null,
  notes text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  deleted_at timestamptz
);
create table if not exists public.routine_exercises (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  day_id uuid not null,
  exercise_id text not null,
  position integer not null,
  target_sets integer not null,
  rep_min integer not null,
  rep_max integer not null,
  rest_sec integer not null,
  priority text not null,
  progression_mode text not null,
  target_rir numeric,
  increment_kg numeric not null,
  start_weight_kg numeric not null,
  notes text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  deleted_at timestamptz
);
create table if not exists public.workout_sessions (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  routine_id uuid,
  routine_day_id uuid,
  name text not null,
  status text not null,
  started_at timestamptz not null,
  ended_at timestamptz,
  difficulty text,
  notes text,
  compressed_minutes integer,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  deleted_at timestamptz
);
create table if not exists public.workout_exercises (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  session_id uuid not null,
  exercise_id text not null,
  routine_exercise_id uuid,
  position integer not null,
  reason_code text,
  reason text,
  recommended_weight_kg numeric,
  recommended_reps integer,
  substitution_for text,
  skipped_reason text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  deleted_at timestamptz
);
create table if not exists public.workout_sets (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  workout_exercise_id uuid not null,
  set_no integer not null,
  set_type text not null,
  weight_kg numeric not null,
  reps integer not null,
  rir numeric,
  completed boolean not null default false,
  excluded_reason text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  deleted_at timestamptz
);
create table if not exists public.progression_decisions (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  exercise_id text not null,
  routine_exercise_id uuid,
  session_id uuid,
  action text not null,
  recommended_weight_kg numeric not null,
  recommended_reps integer not null,
  reason_code text not null,
  reason text not null,
  accepted boolean,
  created_at timestamptz not null
);
create table if not exists public.exercise_substitutions (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  source_exercise_id text not null,
  replacement_exercise_id text not null,
  scope text not null,
  session_id uuid,
  routine_exercise_id uuid,
  created_at timestamptz not null
);
create table if not exists public.import_mappings (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  source text not null,
  source_name text not null,
  exercise_id text not null,
  created_at timestamptz not null,
  unique(user_id,source,source_name)
);
create table if not exists public.entitlement_metadata (
  user_id uuid primary key references auth.users(id) on delete cascade,
  is_pro boolean not null default false,
  source text,
  product_id text,
  expires_at timestamptz,
  updated_at timestamptz not null default now()
);

alter table public.user_profiles enable row level security;
alter table public.routines enable row level security;
alter table public.routine_days enable row level security;
alter table public.routine_exercises enable row level security;
alter table public.workout_sessions enable row level security;
alter table public.workout_exercises enable row level security;
alter table public.workout_sets enable row level security;
alter table public.progression_decisions enable row level security;
alter table public.exercise_substitutions enable row level security;
alter table public.import_mappings enable row level security;
alter table public.entitlement_metadata enable row level security;

do $$
declare t text;
begin
  foreach t in array array['user_profiles','routines','routine_days','routine_exercises','workout_sessions','workout_exercises','workout_sets','progression_decisions','exercise_substitutions','import_mappings','entitlement_metadata']
  loop
    execute format('drop policy if exists own_rows on public.%I',t);
    execute format('create policy own_rows on public.%I for all using (auth.uid() = user_id) with check (auth.uid() = user_id)',t);
  end loop;
end $$;

create index if not exists routines_user_updated_idx on public.routines(user_id,updated_at);
create index if not exists sessions_user_updated_idx on public.workout_sessions(user_id,updated_at);
create index if not exists sets_user_updated_idx on public.workout_sets(user_id,updated_at);
