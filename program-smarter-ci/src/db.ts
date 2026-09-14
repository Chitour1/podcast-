import {EXERCISES,EQUIPMENT_PRESETS} from './data/exercises';
const P:any=require('./domain/progression.js');
export const now=()=>new Date().toISOString();
export const uuid=()=>`${Date.now().toString(36)}-${Math.random().toString(36).slice(2,10)}-${Math.random().toString(36).slice(2,7)}`;

export async function migrate(db:any){
  await db.execAsync(`PRAGMA journal_mode=WAL;PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS app_settings(id INTEGER PRIMARY KEY CHECK(id=1),onboarding INTEGER NOT NULL DEFAULT 0,lang TEXT NOT NULL DEFAULT 'en',units TEXT NOT NULL DEFAULT 'kg',experience TEXT NOT NULL DEFAULT 'intermediate',goal TEXT NOT NULL DEFAULT 'balanced',days INTEGER NOT NULL DEFAULT 3,session_minutes INTEGER NOT NULL DEFAULT 60,equipment_profile TEXT NOT NULL DEFAULT 'commercial',program_mode TEXT NOT NULL DEFAULT 'build',rir_familiar INTEGER NOT NULL DEFAULT 0,theme TEXT NOT NULL DEFAULT 'system',active_routine_id TEXT,supabase_user_id TEXT,sync_state TEXT NOT NULL DEFAULT 'offline',updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS equipment_profiles(id TEXT PRIMARY KEY,name TEXT NOT NULL,is_custom INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS equipment_profile_items(id TEXT PRIMARY KEY,profile_id TEXT NOT NULL,equipment TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,UNIQUE(profile_id,equipment));
CREATE TABLE IF NOT EXISTS exercises(id TEXT PRIMARY KEY,name TEXT NOT NULL,name_ar TEXT,aliases_json TEXT NOT NULL DEFAULT '[]',primary_muscle TEXT NOT NULL,secondary_json TEXT NOT NULL DEFAULT '[]',movement_pattern TEXT NOT NULL,equipment TEXT NOT NULL,unilateral INTEGER NOT NULL DEFAULT 0,rep_min INTEGER NOT NULL,rep_max INTEGER NOT NULL,rest_sec INTEGER NOT NULL,increment_kg REAL NOT NULL,sub_group TEXT NOT NULL,image_key TEXT,is_custom INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS routines(id TEXT PRIMARY KEY,name TEXT NOT NULL,is_active INTEGER NOT NULL DEFAULT 1,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,deleted_at TEXT);
CREATE TABLE IF NOT EXISTS routine_days(id TEXT PRIMARY KEY,routine_id TEXT NOT NULL,name TEXT NOT NULL,position INTEGER NOT NULL,notes TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,deleted_at TEXT);
CREATE TABLE IF NOT EXISTS routine_exercises(id TEXT PRIMARY KEY,day_id TEXT NOT NULL,exercise_id TEXT NOT NULL,position INTEGER NOT NULL,target_sets INTEGER NOT NULL,rep_min INTEGER NOT NULL,rep_max INTEGER NOT NULL,rest_sec INTEGER NOT NULL,priority TEXT NOT NULL DEFAULT 'normal',progression_mode TEXT NOT NULL DEFAULT 'double',target_rir REAL NOT NULL DEFAULT 2,increment_kg REAL NOT NULL,start_weight_kg REAL NOT NULL DEFAULT 20,notes TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,deleted_at TEXT);
CREATE TABLE IF NOT EXISTS workout_sessions(id TEXT PRIMARY KEY,routine_id TEXT,routine_day_id TEXT,name TEXT NOT NULL,status TEXT NOT NULL,started_at TEXT NOT NULL,ended_at TEXT,difficulty TEXT,notes TEXT,rest_end_at TEXT,compressed_minutes INTEGER,import_key TEXT UNIQUE,sync_state TEXT NOT NULL DEFAULT 'pending',created_at TEXT NOT NULL,updated_at TEXT NOT NULL,deleted_at TEXT);
CREATE TABLE IF NOT EXISTS workout_exercises(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,exercise_id TEXT NOT NULL,routine_exercise_id TEXT,position INTEGER NOT NULL,reason_code TEXT,reason TEXT,recommended_weight_kg REAL NOT NULL DEFAULT 0,recommended_reps INTEGER NOT NULL DEFAULT 0,previous_text TEXT,substitution_for TEXT,skipped_reason TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,deleted_at TEXT);
CREATE TABLE IF NOT EXISTS workout_sets(id TEXT PRIMARY KEY,workout_exercise_id TEXT NOT NULL,set_no INTEGER NOT NULL,set_type TEXT NOT NULL DEFAULT 'work',weight_kg REAL NOT NULL DEFAULT 0,reps INTEGER NOT NULL DEFAULT 0,rir REAL,completed INTEGER NOT NULL DEFAULT 0,excluded_reason TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,deleted_at TEXT);
CREATE TABLE IF NOT EXISTS progression_configs(id TEXT PRIMARY KEY,routine_exercise_id TEXT UNIQUE NOT NULL,mode TEXT NOT NULL DEFAULT 'double',target_rir REAL NOT NULL DEFAULT 2,increment_kg REAL NOT NULL,updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS progression_decisions(id TEXT PRIMARY KEY,exercise_id TEXT NOT NULL,routine_exercise_id TEXT,session_id TEXT,action TEXT NOT NULL,recommended_weight_kg REAL NOT NULL,recommended_reps INTEGER NOT NULL,reason_code TEXT NOT NULL,reason TEXT NOT NULL,accepted INTEGER,created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS exercise_substitutions(id TEXT PRIMARY KEY,source_exercise_id TEXT NOT NULL,replacement_exercise_id TEXT NOT NULL,scope TEXT NOT NULL,session_id TEXT,routine_exercise_id TEXT,created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS import_jobs(id TEXT PRIMARY KEY,source TEXT NOT NULL,file_name TEXT,status TEXT NOT NULL,workouts_found INTEGER NOT NULL DEFAULT 0,exercises_found INTEGER NOT NULL DEFAULT 0,sets_found INTEGER NOT NULL DEFAULT 0,unmatched INTEGER NOT NULL DEFAULT 0,duplicates INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,completed_at TEXT);
CREATE TABLE IF NOT EXISTS import_mappings(id TEXT PRIMARY KEY,source TEXT NOT NULL,source_name TEXT NOT NULL,exercise_id TEXT NOT NULL,created_at TEXT NOT NULL,UNIQUE(source,source_name));
CREATE TABLE IF NOT EXISTS sync_queue(id TEXT PRIMARY KEY,table_name TEXT NOT NULL,record_id TEXT NOT NULL,op TEXT NOT NULL,payload_json TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(table_name,record_id,op));
CREATE TABLE IF NOT EXISTS entitlement_metadata(id INTEGER PRIMARY KEY CHECK(id=1),is_pro INTEGER NOT NULL DEFAULT 0,source TEXT,product_id TEXT,expires_at TEXT,last_verified_at TEXT,cached_until TEXT);
CREATE INDEX IF NOT EXISTS idx_sessions_status ON workout_sessions(status,started_at);
CREATE INDEX IF NOT EXISTS idx_we_session ON workout_exercises(session_id,position);
CREATE INDEX IF NOT EXISTS idx_sets_we ON workout_sets(workout_exercise_id,set_no);
CREATE INDEX IF NOT EXISTS idx_re_day ON routine_exercises(day_id,position);
CREATE INDEX IF NOT EXISTS idx_decisions_ex ON progression_decisions(exercise_id,created_at);
`);
  await db.runAsync(`INSERT OR IGNORE INTO app_settings(id,updated_at) VALUES(1,?)`,now());
  await db.runAsync(`INSERT OR IGNORE INTO entitlement_metadata(id,is_pro) VALUES(1,0)`);
  for(const [pid,list] of Object.entries(EQUIPMENT_PRESETS)){
    const t=now(); await db.runAsync(`INSERT OR IGNORE INTO equipment_profiles(id,name,is_custom,created_at,updated_at)VALUES(?,?,?,?,?)`,pid,pid==='commercial'?'Commercial Gym':pid==='home'?'Home Gym':'Custom',pid==='custom'?1:0,t,t);
    for(const eq of list) await db.runAsync(`INSERT OR IGNORE INTO equipment_profile_items(id,profile_id,equipment,enabled)VALUES(?,?,?,1)`,uuid(),pid,eq);
  }
  for(const e of EXERCISES){const t=now();await db.runAsync(`INSERT OR IGNORE INTO exercises(id,name,name_ar,aliases_json,primary_muscle,secondary_json,movement_pattern,equipment,unilateral,rep_min,rep_max,rest_sec,increment_kg,sub_group,image_key,is_custom,created_at,updated_at)VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,e.id,e.name,e.name_ar,JSON.stringify(e.aliases),e.primary,JSON.stringify(e.secondary),e.pattern,e.equipment,e.unilateral?1:0,e.repMin,e.repMax,e.rest,e.increment,e.subGroup,e.imageKey,0,t,t)}
}

export async function getSettings(db:any){return await db.getFirstAsync<any>(`SELECT * FROM app_settings WHERE id=1`)}
export async function equipmentFor(db:any,profile:string){return (await db.getAllAsync<any>(`SELECT equipment FROM equipment_profile_items WHERE profile_id=? AND enabled=1`,profile)).map((x:any)=>x.equipment)}

const TEMPLATES:any={
  2:[['Full Body A',['back_squat','bench_press','cable_row','lateral_raise','triceps_pushdown']],['Full Body B',['romanian_deadlift','ohp','lat_pulldown','split_squat','db_curl']]],
  3:[['Full Body A',['back_squat','bench_press','cable_row','lateral_raise']],['Full Body B',['romanian_deadlift','ohp','lat_pulldown','leg_extension']],['Full Body C',['leg_press','incline_db','db_row','leg_curl','triceps_pushdown']]],
  4:[['Upper A',['bench_press','barbell_row','ohp','lat_pulldown','db_curl']],['Lower A',['back_squat','romanian_deadlift','leg_press','standing_calf']],['Upper B',['incline_db','cable_row','db_shoulder_press','pull_up','triceps_pushdown']],['Lower B',['deadlift','split_squat','leg_curl','leg_extension','seated_calf']]],
  5:[['Push',['bench_press','ohp','incline_db','lateral_raise','triceps_pushdown']],['Pull',['pull_up','barbell_row','lat_pulldown','face_pull','db_curl']],['Legs',['back_squat','romanian_deadlift','leg_press','leg_curl','standing_calf']],['Upper',['incline_bench','cable_row','db_shoulder_press','cable_lateral','barbell_curl']],['Lower',['deadlift','front_squat','leg_extension','lying_leg_curl','seated_calf']]],
  6:[['Push A',['bench_press','ohp','incline_db','lateral_raise','triceps_pushdown']],['Pull A',['pull_up','barbell_row','lat_pulldown','face_pull','db_curl']],['Legs A',['back_squat','romanian_deadlift','leg_press','standing_calf']],['Push B',['incline_bench','db_shoulder_press','machine_press','cable_lateral','overhead_triceps']],['Pull B',['cable_row','lat_pulldown','db_row','rear_delt_fly','hammer_curl']],['Legs B',['deadlift','front_squat','split_squat','leg_curl','seated_calf']]]
};

function starterWeight(id:string,experience:string){const m:any={back_squat:60,front_squat:45,bench_press:40,deadlift:70,romanian_deadlift:50,ohp:25,leg_press:80,pull_up:0};let b=m[id]??20;if(experience==='beginner')b*=.65;if(experience==='advanced')b*=1.35;return Math.round(b*2)/2}

export async function createDeterministicRoutine(db:any,opts:{goal:string;experience:string;days:number;sessionMinutes:number;equipmentProfile:string;name?:string}){
  const allowed=new Set(await equipmentFor(db,opts.equipmentProfile)); const n=Math.min(6,Math.max(2,opts.days)); let tpl=(TEMPLATES[n]||TEMPLATES[3]);
  const rid=uuid(),t=now(); await db.runAsync(`UPDATE routines SET is_active=0,updated_at=? WHERE is_active=1`,t); await db.runAsync(`INSERT INTO routines(id,name,is_active,created_at,updated_at)VALUES(?,?,1,?,?)`,rid,opts.name||'Program Smarter',t,t);
  let dp=0;
  for(const [dayName,ids] of tpl){const did=uuid();await db.runAsync(`INSERT INTO routine_days(id,routine_id,name,position,created_at,updated_at)VALUES(?,?,?,?,?,?)`,did,rid,dayName,dp++,t,t);let pos=0;const maxEx=opts.sessionMinutes<=30?3:opts.sessionMinutes<=45?4:5;
    for(let exId of (ids as string[])){
      let e=await db.getFirstAsync<any>(`SELECT * FROM exercises WHERE id=?`,exId); if(!e)continue;
      if(allowed.size && !allowed.has(e.equipment) && e.equipment!=='Bodyweight'){
        const placeholders=[...allowed].map(()=>'?').join(',');
        const alt=await db.getFirstAsync<any>(`SELECT * FROM exercises WHERE sub_group=? AND (equipment IN (${placeholders}) OR equipment='Bodyweight') ORDER BY CASE WHEN movement_pattern=? THEN 0 ELSE 1 END LIMIT 1`,e.sub_group,...[...allowed],e.movement_pattern); if(alt)e=alt; else continue;
      }
      if(pos>=maxEx)break; const main=pos<2; let sets=main?3:2; let rmin=e.rep_min,rmax=e.rep_max;
      if(opts.goal==='strength'&&main){sets=opts.experience==='advanced'?4:3;rmin=Math.max(3,e.rep_min-2);rmax=Math.max(rmin+2,Math.min(8,e.rep_max-2))}
      if(opts.goal==='hypertrophy'&&!main){sets=3;rmin=Math.max(8,e.rep_min);rmax=Math.max(12,e.rep_max)}
      const reid=uuid(); const priority=pos<2?'high':pos===maxEx-1?'optional':'normal'; const sw=starterWeight(e.id,opts.experience);
      await db.runAsync(`INSERT INTO routine_exercises(id,day_id,exercise_id,position,target_sets,rep_min,rep_max,rest_sec,priority,progression_mode,target_rir,increment_kg,start_weight_kg,created_at,updated_at)VALUES(?,?,?,?,?,?,?,?,?,'double',2,?,?,?,?)`,reid,did,e.id,pos,sets,rmin,rmax,e.rest_sec,priority,e.increment_kg,sw,t,t);
      await db.runAsync(`INSERT OR REPLACE INTO progression_configs(id,routine_exercise_id,mode,target_rir,increment_kg,updated_at)VALUES(?,?, 'double',2,?,?)`,uuid(),reid,e.increment_kg,t);pos++;
    }
  }
  await db.runAsync(`UPDATE app_settings SET active_routine_id=?,updated_at=? WHERE id=1`,rid,t); return rid;
}

export async function createBlankRoutine(db:any,name='My Program'){
 const rid=uuid(),t=now();await db.runAsync(`UPDATE routines SET is_active=0,updated_at=? WHERE is_active=1`,t);await db.runAsync(`INSERT INTO routines(id,name,is_active,created_at,updated_at)VALUES(?,?,1,?,?)`,rid,name,t,t);await db.runAsync(`UPDATE app_settings SET active_routine_id=?,updated_at=? WHERE id=1`,rid,t);return rid;
}

export async function nextRoutineDay(db:any){
 const st=await getSettings(db);let rid=st?.active_routine_id;if(!rid){const r=await db.getFirstAsync<any>(`SELECT id FROM routines WHERE is_active=1 AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 1`);rid=r?.id}if(!rid)return null;
 const days=await db.getAllAsync<any>(`SELECT * FROM routine_days WHERE routine_id=? AND deleted_at IS NULL ORDER BY position`,rid);if(!days.length)return null;
 const last=await db.getFirstAsync<any>(`SELECT routine_day_id FROM workout_sessions WHERE routine_id=? AND status='completed' AND deleted_at IS NULL ORDER BY ended_at DESC LIMIT 1`,rid); if(!last?.routine_day_id)return days[0]; const idx=days.findIndex((d:any)=>d.id===last.routine_day_id); return days[(idx+1)%days.length];
}

async function exposureRows(db:any,exerciseId:string){
 const sessions=await db.getAllAsync<any>(`SELECT s.id,s.ended_at,we.id weid,we.skipped_reason,we.substitution_for FROM workout_sessions s JOIN workout_exercises we ON we.session_id=s.id WHERE s.status='completed' AND we.exercise_id=? AND s.deleted_at IS NULL ORDER BY s.ended_at DESC LIMIT 6`,exerciseId);const out=[];for(const x of sessions){const sets=await db.getAllAsync<any>(`SELECT set_type type,weight_kg weightKg,reps,rir,completed,excluded_reason excludedReason FROM workout_sets WHERE workout_exercise_id=? AND deleted_at IS NULL ORDER BY set_no`,x.weid);out.push({endedAt:x.ended_at,excluded:!!x.skipped_reason||!!x.substitution_for,sets})}return out;
}

export async function recommendationFor(db:any,re:any){
 const exposures=await exposureRows(db,re.exercise_id);let daysSinceLast:any=null;if(exposures[0]?.endedAt)daysSinceLast=Math.floor((Date.now()-new Date(exposures[0].endedAt).getTime())/86400000);
 return P.decideProgression({repMin:re.rep_min,repMax:re.rep_max,targetSets:re.target_sets,targetRir:re.target_rir??2,incrementKg:re.increment_kg,startWeightKg:re.start_weight_kg},exposures,{daysSinceLast});
}

export async function startWorkout(db:any){
 const active=await db.getFirstAsync<any>(`SELECT id FROM workout_sessions WHERE status='active' AND deleted_at IS NULL ORDER BY started_at DESC LIMIT 1`);if(active)return active.id;
 const day=await nextRoutineDay(db);if(!day)throw new Error('NO_ROUTINE'); const routine=await db.getFirstAsync<any>(`SELECT routine_id FROM routine_days WHERE id=?`,day.id);const sid=uuid(),t=now();await db.runAsync(`INSERT INTO workout_sessions(id,routine_id,routine_day_id,name,status,started_at,sync_state,created_at,updated_at)VALUES(?,?,?,?, 'active',?,'pending',?,?)`,sid,routine?.routine_id,day.id,day.name,t,t,t);
 const list=await db.getAllAsync<any>(`SELECT re.*,e.name,e.name_ar,e.image_key FROM routine_exercises re JOIN exercises e ON e.id=re.exercise_id WHERE re.day_id=? AND re.deleted_at IS NULL ORDER BY re.position`,day.id);
 for(const re of list){const d=await recommendationFor(db,re);const prev=await db.getFirstAsync<any>(`SELECT ws.weight_kg,ws.reps FROM workout_sets ws JOIN workout_exercises we ON we.id=ws.workout_exercise_id JOIN workout_sessions s ON s.id=we.session_id WHERE we.exercise_id=? AND ws.completed=1 AND ws.set_type='work' AND s.status='completed' ORDER BY s.ended_at DESC,ws.set_no DESC LIMIT 1`,re.exercise_id);const wid=uuid();await db.runAsync(`INSERT INTO workout_exercises(id,session_id,exercise_id,routine_exercise_id,position,reason_code,reason,recommended_weight_kg,recommended_reps,previous_text,created_at,updated_at)VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`,wid,sid,re.exercise_id,re.id,re.position,d.reasonCode,d.reason,d.weightKg,d.targetReps,prev?`${prev.weight_kg} kg × ${prev.reps}`:null,t,t);for(let n=1;n<=re.target_sets;n++)await db.runAsync(`INSERT INTO workout_sets(id,workout_exercise_id,set_no,set_type,weight_kg,reps,created_at,updated_at)VALUES(?,?,?,?,?,?,?,?)`,uuid(),wid,n,'work',d.weightKg,d.targetReps,t,t)}
 return sid;
}

export async function enqueue(db:any,tableName:string,recordId:string,op:string,payload:any){const t=now();await db.runAsync(`INSERT OR REPLACE INTO sync_queue(id,table_name,record_id,op,payload_json,attempts,next_attempt_at,created_at,updated_at)VALUES(COALESCE((SELECT id FROM sync_queue WHERE table_name=? AND record_id=? AND op=?),?),?,?,?,?,0,NULL,COALESCE((SELECT created_at FROM sync_queue WHERE table_name=? AND record_id=? AND op=?),?),?)`,tableName,recordId,op,uuid(),tableName,recordId,op,JSON.stringify(payload),tableName,recordId,op,t,t)}
