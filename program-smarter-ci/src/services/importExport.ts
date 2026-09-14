import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
import {now,uuid} from '../db';

export type ImportPreview={source:string;fileName:string;rows:any[];workouts:number;exercises:number;sets:number;unmatched:string[];duplicates:number};

function parseCsv(text:string){
  const rows:string[][]=[];let row:string[]=[],cell='',quote=false;
  for(let i=0;i<text.length;i++){const c=text[i];if(c==='"'){if(quote&&text[i+1]==='"'){cell+='"';i++}else quote=!quote}else if(c===','&&!quote){row.push(cell);cell=''}else if((c==='\n'||c==='\r')&&!quote){if(c==='\r'&&text[i+1]==='\n')i++;row.push(cell);if(row.some(x=>x.trim()))rows.push(row);row=[];cell=''}else cell+=c}
  row.push(cell);if(row.some(x=>x.trim()))rows.push(row);if(!rows.length)return[];
  const h=rows.shift()!.map(x=>x.trim());return rows.map(r=>Object.fromEntries(h.map((k,i)=>[k,(r[i]??'').trim()])));
}
const norm=(s:any)=>String(s??'').toLowerCase().replace(/[^a-z0-9\u0600-\u06ff]+/g,' ').trim();
function lev(a:string,b:string){a=norm(a);b=norm(b);const d=Array.from({length:a.length+1},()=>Array(b.length+1).fill(0));for(let i=0;i<=a.length;i++)d[i][0]=i;for(let j=0;j<=b.length;j++)d[0][j]=j;for(let i=1;i<=a.length;i++)for(let j=1;j<=b.length;j++)d[i][j]=Math.min(d[i-1][j]+1,d[i][j-1]+1,d[i-1][j-1]+(a[i-1]===b[j-1]?0:1));return d[a.length][b.length]}
function pick(obj:any,keys:string[]){for(const k of keys)if(obj[k]!=null&&String(obj[k]).trim()!=='')return obj[k];const nk=Object.keys(obj).find(k=>keys.map(norm).includes(norm(k)));return nk?obj[nk]:''}
function sourceFrom(headers:string[]){const s=headers.map(norm).join('|');if(s.includes('exercise title')||s.includes('start time'))return'hevy';if(s.includes('workout name')||s.includes('set order'))return'strong';return'generic'}
function toIso(v:any){const d=new Date(v);return Number.isNaN(d.getTime())?new Date().toISOString():d.toISOString()}

export async function pickAndPreview(db:any):Promise<ImportPreview|null>{
  const res=await DocumentPicker.getDocumentAsync({type:['text/csv','text/comma-separated-values','text/plain'],copyToCacheDirectory:true,multiple:false});if(res.canceled)return null;const a=res.assets[0];const text=await FileSystem.readAsStringAsync(a.uri,{encoding:FileSystem.EncodingType.UTF8});const parsed=parseCsv(text);if(!parsed.length)throw new Error('EMPTY');const source=sourceFrom(Object.keys(parsed[0]));
  const lib=await db.getAllAsync<any>(`SELECT id,name,name_ar,aliases_json FROM exercises`);
  const aliases:any[]=[];for(const e of lib){aliases.push({id:e.id,name:e.name,n:norm(e.name)});if(e.name_ar)aliases.push({id:e.id,name:e.name,n:norm(e.name_ar)});for(const x of JSON.parse(e.aliases_json||'[]'))aliases.push({id:e.id,name:e.name,n:norm(x)})}
  const rows=parsed.map((r:any)=>{
    const exercise=pick(r,['Exercise Title','Exercise Name','Exercise','exercise','exercise_name']);const workout=pick(r,['Title','Workout Name','Workout','workout','routine']);const date=pick(r,['Start Time','Date','date','started_at','Workout Date']);let weight=Number(pick(r,['Weight (kg)','Weight','weight_kg','weight','Weight (lbs)','weight_lbs']))||0;const weightKey=Object.keys(r).find(k=>norm(k).includes('weight'));if(weightKey&&norm(weightKey).includes('lb'))weight/=2.2046226218;const reps=Number(pick(r,['Reps','reps','repetitions']))||0;const rpe=Number(pick(r,['RPE','rpe']))||0;let best=aliases.find(x=>x.n===norm(exercise));if(!best&&exercise){const ranked=aliases.map(x=>({...x,d:lev(x.n,norm(exercise))})).sort((a,b)=>a.d-b.d);if(ranked[0]&&ranked[0].d<=Math.max(2,Math.floor(norm(exercise).length*.22)))best=ranked[0]}
    return{date:toIso(date),workout:workout||'Imported Workout',exercise,exerciseId:best?.id||null,weightKg:Math.round(weight*100)/100,reps,rpe:rpe||null,setType:norm(pick(r,['Set Type','set_type','Type']))||'work'};
  }).filter((r:any)=>r.exercise&&r.reps>=0);
  const keys=new Set(rows.map((r:any)=>`${r.date.slice(0,16)}|${norm(r.workout)}`));const exs=new Set(rows.map((r:any)=>norm(r.exercise)));const unmatched=[...new Set(rows.filter((r:any)=>!r.exerciseId).map((r:any)=>r.exercise))] as string[];let duplicates=0;for(const k of keys){const found=await db.getFirstAsync<any>(`SELECT id FROM workout_sessions WHERE import_key=?`,`${source}|${k}`);if(found)duplicates++}
  return{source,fileName:a.name||'import.csv',rows,workouts:keys.size,exercises:exs.size,sets:rows.length,unmatched,duplicates};
}

export async function importPreview(db:any,p:ImportPreview){
  const jid=uuid(),t=now();await db.runAsync(`INSERT INTO import_jobs(id,source,file_name,status,workouts_found,exercises_found,sets_found,unmatched,duplicates,created_at)VALUES(?,?,?,'running',?,?,?,?,?,?)`,jid,p.source,p.fileName,p.workouts,p.exercises,p.sets,p.unmatched.length,p.duplicates,t);
  const groups=new Map<string,any[]>();for(const r of p.rows){const k=`${r.date.slice(0,16)}|${norm(r.workout)}`;if(!groups.has(k))groups.set(k,[]);groups.get(k)!.push(r)}
  let imported=0;
  await db.withTransactionAsync(async()=>{for(const [k,rows] of groups){const importKey=`${p.source}|${k}`;const exists=await db.getFirstAsync<any>(`SELECT id FROM workout_sessions WHERE import_key=?`,importKey);if(exists)continue;const sid=uuid(),start=rows[0].date,end=new Date(new Date(start).getTime()+60*60000).toISOString();await db.runAsync(`INSERT INTO workout_sessions(id,name,status,started_at,ended_at,difficulty,import_key,sync_state,created_at,updated_at)VALUES(?,?, 'completed',?,?,'good',?,'pending',?,?)`,sid,rows[0].workout,start,end,importKey,t,t);
    const byEx=new Map<string,any[]>();for(const r of rows){if(!byEx.has(r.exercise))byEx.set(r.exercise,[]);byEx.get(r.exercise)!.push(r)}let pos=0;for(const [name,sets] of byEx){let eid=sets[0].exerciseId;if(!eid){eid=`custom-${uuid()}`;await db.runAsync(`INSERT INTO exercises(id,name,name_ar,aliases_json,primary_muscle,secondary_json,movement_pattern,equipment,unilateral,rep_min,rep_max,rest_sec,increment_kg,sub_group,image_key,is_custom,created_at,updated_at)VALUES(?,?,NULL,'[]','Other','[]','other','Other',0,6,15,90,1,'custom','fallback',1,?,?)`,eid,name,t,t);await db.runAsync(`INSERT OR REPLACE INTO import_mappings(id,source,source_name,exercise_id,created_at)VALUES(?,?,?,?,?)`,uuid(),p.source,name,eid,t)}const wid=uuid();await db.runAsync(`INSERT INTO workout_exercises(id,session_id,exercise_id,position,recommended_weight_kg,recommended_reps,reason_code,reason,created_at,updated_at)VALUES(?,?,?,?,0,0,'import','Imported history',?,?)`,wid,sid,eid,pos++,t,t);let n=1;for(const s of sets){const rir=s.rpe?Math.max(0,10-Number(s.rpe)):null;const type=['warmup','drop','failure','amrap','work'].find(x=>s.setType.includes(x))||'work';await db.runAsync(`INSERT INTO workout_sets(id,workout_exercise_id,set_no,set_type,weight_kg,reps,rir,completed,created_at,updated_at)VALUES(?,?,?,?,?,?,?,1,?,?)`,uuid(),wid,n++,type,s.weightKg,s.reps,rir,t,t)}}imported++}
  });await db.runAsync(`UPDATE import_jobs SET status='completed',completed_at=? WHERE id=?`,now(),jid);return imported;
}

function csvEsc(v:any){const s=String(v??'');return /[",\n]/.test(s)?`"${s.replace(/"/g,'""')}"`:s}
export async function exportCsv(db:any){
  const rows=await db.getAllAsync<any>(`SELECT s.started_at,s.name workout,e.name exercise,ws.set_no,ws.set_type,ws.weight_kg,ws.reps,ws.rir,s.difficulty FROM workout_sessions s JOIN workout_exercises we ON we.session_id=s.id JOIN exercises e ON e.id=we.exercise_id JOIN workout_sets ws ON ws.workout_exercise_id=we.id WHERE s.status='completed' AND s.deleted_at IS NULL ORDER BY s.started_at,we.position,ws.set_no`);const header=['date','workout','exercise','set','type','weight_kg','reps','rir','difficulty'];const text=[header.join(','),...rows.map((r:any)=>[r.started_at,r.workout,r.exercise,r.set_no,r.set_type,r.weight_kg,r.reps,r.rir??'',r.difficulty??''].map(csvEsc).join(','))].join('\n');const uri=`${FileSystem.cacheDirectory}program-smarter-export.csv`;await FileSystem.writeAsStringAsync(uri,text,{encoding:FileSystem.EncodingType.UTF8});if(await Sharing.isAvailableAsync())await Sharing.shareAsync(uri,{mimeType:'text/csv',dialogTitle:'Program Smarter CSV'});return uri;
}
export async function exportJson(db:any){
  const tables=['app_settings','equipment_profiles','equipment_profile_items','exercises','routines','routine_days','routine_exercises','workout_sessions','workout_exercises','workout_sets','progression_decisions','exercise_substitutions','import_mappings'];const out:any={schemaVersion:1,exportedAt:now(),data:{}};for(const t of tables)out.data[t]=await db.getAllAsync<any>(`SELECT * FROM ${t}`);const uri=`${FileSystem.cacheDirectory}program-smarter-export.json`;await FileSystem.writeAsStringAsync(uri,JSON.stringify(out,null,2),{encoding:FileSystem.EncodingType.UTF8});if(await Sharing.isAvailableAsync())await Sharing.shareAsync(uri,{mimeType:'application/json',dialogTitle:'Program Smarter JSON'});return uri;
}
