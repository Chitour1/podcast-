import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {
  Alert,
  Appearance,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {StatusBar} from 'expo-status-bar';
import {SQLiteProvider, useSQLiteContext} from 'expo-sqlite';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';

const C={bg:'#F4F6F8',card:'#FFFFFF',ink:'#101828',muted:'#667085',line:'#E4E7EC',blue:'#175CD3',green:'#067647',red:'#B42318',amber:'#B54708',soft:'#EEF4FF'};
const now=()=>new Date().toISOString();
const id=()=>`${Date.now().toString(36)}-${Math.random().toString(36).slice(2,10)}-${Math.random().toString(36).slice(2,8)}`;
const epley=(w:number,r:number)=>r<=1?w:w*(1+r/30);

const words={
  en:{home:'Home',routine:'Routine',history:'History',progress:'Progress',settings:'Settings',tag:'Your Program. Smarter.',start:'Start workout',resume:'Resume workout',build:'Build starter program',why:'Why?',busy:'Busy',finish:'Finish workout',less:'Less time',done:'Done',weight:'Weight',reps:'Reps',rest:'Rest',easy:'Easy',good:'Good',hard:'Hard',noRoutine:'No active routine yet.',today:'Next workout',language:'Language',units:'Units',reset:'Reset local demo data'},
  ar:{home:'الرئيسية',routine:'البرنامج',history:'السجل',progress:'التقدم',settings:'الإعدادات',tag:'برنامجك. أذكى.',start:'ابدأ التمرين',resume:'استأنف التمرين',build:'أنشئ برنامجًا ابتدائيًا',why:'لماذا؟',busy:'مشغول',finish:'إنهاء التمرين',less:'وقت أقل',done:'تم',weight:'الوزن',reps:'التكرارات',rest:'راحة',easy:'سهل',good:'جيد',hard:'صعب',noRoutine:'لا يوجد برنامج نشط بعد.',today:'التمرين التالي',language:'اللغة',units:'الوحدات',reset:'إعادة ضبط البيانات المحلية'},
};

type Lang='en'|'ar';
type Tab='home'|'routine'|'history'|'progress'|'settings';

async function migrate(db:any){
  await db.execAsync(`
    PRAGMA journal_mode=WAL;
    PRAGMA foreign_keys=ON;
    CREATE TABLE IF NOT EXISTS settings(id INTEGER PRIMARY KEY CHECK(id=1), onboarding INTEGER NOT NULL DEFAULT 0, lang TEXT NOT NULL DEFAULT 'en', units TEXT NOT NULL DEFAULT 'kg', goal TEXT NOT NULL DEFAULT 'balanced', days INTEGER NOT NULL DEFAULT 3, updated_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS exercises(id TEXT PRIMARY KEY,name TEXT NOT NULL,primary_muscle TEXT NOT NULL,pattern TEXT NOT NULL,equipment TEXT NOT NULL,rep_min INTEGER NOT NULL,rep_max INTEGER NOT NULL,rest_sec INTEGER NOT NULL,increment_kg REAL NOT NULL,sub_group TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS routine_days(id TEXT PRIMARY KEY,name TEXT NOT NULL,position INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS routine_exercises(id TEXT PRIMARY KEY,day_id TEXT NOT NULL,exercise_id TEXT NOT NULL,position INTEGER NOT NULL,target_sets INTEGER NOT NULL,rep_min INTEGER NOT NULL,rep_max INTEGER NOT NULL,rest_sec INTEGER NOT NULL,priority TEXT NOT NULL,increment_kg REAL NOT NULL,start_weight_kg REAL NOT NULL, FOREIGN KEY(day_id) REFERENCES routine_days(id));
    CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,day_id TEXT,name TEXT NOT NULL,status TEXT NOT NULL,started_at TEXT NOT NULL,ended_at TEXT,difficulty TEXT,rest_end_at TEXT,updated_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS workout_exercises(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,exercise_id TEXT NOT NULL,routine_exercise_id TEXT,position INTEGER NOT NULL,reason TEXT NOT NULL,recommended_weight REAL NOT NULL,recommended_reps INTEGER NOT NULL,substitution_for TEXT,skipped_reason TEXT, FOREIGN KEY(session_id) REFERENCES sessions(id));
    CREATE TABLE IF NOT EXISTS workout_sets(id TEXT PRIMARY KEY,workout_exercise_id TEXT NOT NULL,set_no INTEGER NOT NULL,set_type TEXT NOT NULL DEFAULT 'work',weight_kg REAL NOT NULL,reps INTEGER NOT NULL,rir REAL,completed INTEGER NOT NULL DEFAULT 0,excluded_reason TEXT,updated_at TEXT NOT NULL, FOREIGN KEY(workout_exercise_id) REFERENCES workout_exercises(id));
    CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(status,started_at);
    CREATE INDEX IF NOT EXISTS idx_sets_we ON workout_sets(workout_exercise_id,set_no);
  `);
  await db.runAsync(`INSERT OR IGNORE INTO settings(id,updated_at) VALUES(1,?)`,now());
  const ex=[
    ['squat','Back Squat','quads','squat','barbell',5,10,180,2.5,'squat'],
    ['leg_press','Leg Press','quads','squat','machine',8,15,120,5,'squat'],
    ['bench','Barbell Bench Press','chest','horizontal_push','barbell',6,10,180,2.5,'press'],
    ['db_bench','Dumbbell Bench Press','chest','horizontal_push','dumbbell',8,12,120,2,'press'],
    ['row','Seated Cable Row','back','horizontal_pull','cable',8,12,120,2.5,'row'],
    ['db_row','Dumbbell Row','back','horizontal_pull','dumbbell',8,12,120,2,'row'],
    ['rdl','Romanian Deadlift','hamstrings','hinge','barbell',6,10,150,2.5,'hinge'],
    ['lat','Lat Pulldown','lats','vertical_pull','cable',8,12,120,2.5,'vertical_pull'],
    ['ohp','Overhead Press','shoulders','vertical_push','barbell',6,10,150,2.5,'vertical_push'],
    ['lateral','Lateral Raise','shoulders','isolation','dumbbell',10,20,75,1,'lateral'],
    ['curl','Dumbbell Curl','biceps','isolation','dumbbell',8,15,75,1,'curl'],
    ['pushdown','Triceps Pushdown','triceps','isolation','cable',8,15,75,2.5,'triceps'],
  ];
  for(const x of ex) await db.runAsync(`INSERT OR IGNORE INTO exercises(id,name,primary_muscle,pattern,equipment,rep_min,rep_max,rest_sec,increment_kg,sub_group) VALUES(?,?,?,?,?,?,?,?,?,?)`,...x);
}

async function createStarter(db:any,goal:string,days:number){
  await db.withTransactionAsync(async()=>{
    await db.execAsync(`DELETE FROM workout_sets;DELETE FROM workout_exercises;DELETE FROM sessions;DELETE FROM routine_exercises;DELETE FROM routine_days;`);
    const templates=days<=2?[
      ['Full Body A',['squat','bench','row','lateral']],['Full Body B',['rdl','ohp','lat','curl']]
    ]:days===3?[
      ['Full Body A',['squat','bench','row']],['Full Body B',['rdl','ohp','lat']],['Full Body C',['leg_press','db_bench','db_row','lateral']]
    ]:[
      ['Upper A',['bench','row','ohp','lat']],['Lower A',['squat','rdl','leg_press']],['Upper B',['ohp','db_row','db_bench','lateral']],['Lower B',['rdl','leg_press','squat']]
    ].slice(0,Math.min(days,4));
    let p=0;
    for(const [name,list] of templates as any[]){
      const did=id(); await db.runAsync(`INSERT INTO routine_days(id,name,position) VALUES(?,?,?)`,did,name,p++);
      let ep=0;
      for(const exId of list){
        const e=await db.getFirstAsync<any>(`SELECT * FROM exercises WHERE id=?`,exId);
        const main=ep<2; const strength=goal==='strength';
        const sets=main?(strength?4:3):3;
        const rmin=main&&strength?4:e.rep_min; const rmax=main&&strength?6:e.rep_max;
        const start=exId==='squat'?60:exId==='bench'?40:exId==='rdl'?50:exId==='leg_press'?80:exId==='ohp'?25:20;
        await db.runAsync(`INSERT INTO routine_exercises(id,day_id,exercise_id,position,target_sets,rep_min,rep_max,rest_sec,priority,increment_kg,start_weight_kg) VALUES(?,?,?,?,?,?,?,?,?,?,?)`,id(),did,exId,ep,sets,rmin,rmax,e.rest_sec,ep<2?'high':ep===list.length-1?'optional':'normal',e.increment_kg,start);
        ep++;
      }
    }
  });
}

function roundInc(v:number,inc:number){return Math.round(v/inc)*inc;}
async function recommendation(db:any,re:any){
  const rows=await db.getAllAsync<any>(`SELECT s.id session_id,s.started_at,s.difficulty,ws.weight_kg,ws.reps,ws.rir,ws.completed,ws.excluded_reason FROM sessions s JOIN workout_exercises we ON we.session_id=s.id JOIN workout_sets ws ON ws.workout_exercise_id=we.id WHERE s.status='completed' AND we.exercise_id=? AND ws.set_type='work' ORDER BY s.started_at DESC,ws.set_no`,re.exercise_id);
  const groups=new Map<string,any>();
  for(const r of rows){if(!groups.has(r.session_id))groups.set(r.session_id,{difficulty:r.difficulty,sets:[]});groups.get(r.session_id).sets.push(r);}
  const hist=[...groups.values()].slice(0,2);
  const current=rows.find((r:any)=>r.completed)?.weight_kg??re.start_weight_kg;
  const qualifies=(g:any)=>g&&g.sets.filter((s:any)=>s.completed&&!s.excluded_reason).length>=re.target_sets&&g.sets.filter((s:any)=>s.completed&&!s.excluded_reason).slice(0,re.target_sets).every((s:any)=>s.reps>=re.rep_max&&(s.rir==null||s.rir>=2))&&g.difficulty!=='hard';
  const fail=(g:any)=>g&&g.sets.filter((s:any)=>s.completed&&!s.excluded_reason).some((s:any)=>s.reps<re.rep_min);
  if(qualifies(hist[0])&&qualifies(hist[1])) return {weight:roundInc(current+re.increment_kg,re.increment_kg),reps:re.rep_min,reason:`Two consecutive exposures reached the top of ${re.rep_min}–${re.rep_max} with enough reserve. Increase by ${re.increment_kg} kg.`};
  if(fail(hist[0])&&fail(hist[1])) return {weight:Math.max(re.increment_kg,roundInc(current-re.increment_kg,re.increment_kg)),reps:re.rep_min,reason:`Targets were missed twice in a row. Use one small ${re.increment_kg} kg reduction; no aggressive reset.`};
  const best=hist[0]?.sets?.filter((s:any)=>s.completed&&!s.excluded_reason).reduce((m:number,s:any)=>Math.max(m,s.reps),re.rep_min-1)??re.rep_min-1;
  return {weight:current,reps:Math.min(re.rep_max,Math.max(re.rep_min,best+1)),reason:`Keep the load and build reps inside ${re.rep_min}–${re.rep_max} before increasing weight.`};
}

async function startWorkout(db:any){
  const active=await db.getFirstAsync<any>(`SELECT id FROM sessions WHERE status='active' ORDER BY started_at DESC LIMIT 1`); if(active)return active.id;
  const day=await db.getFirstAsync<any>(`SELECT * FROM routine_days ORDER BY position LIMIT 1`); if(!day)throw new Error('NO_ROUTINE');
  const sid=id(),t=now(); await db.runAsync(`INSERT INTO sessions(id,day_id,name,status,started_at,updated_at) VALUES(?,?,?,'active',?,?)`,sid,day.id,day.name,t,t);
  const list=await db.getAllAsync<any>(`SELECT re.*,e.name FROM routine_exercises re JOIN exercises e ON e.id=re.exercise_id WHERE re.day_id=? ORDER BY re.position`,day.id);
  for(const re of list){
    const rec=await recommendation(db,re); const wid=id();
    await db.runAsync(`INSERT INTO workout_exercises(id,session_id,exercise_id,routine_exercise_id,position,reason,recommended_weight,recommended_reps) VALUES(?,?,?,?,?,?,?,?)`,wid,sid,re.exercise_id,re.id,re.position,rec.reason,rec.weight,rec.reps);
    for(let n=1;n<=re.target_sets;n++)await db.runAsync(`INSERT INTO workout_sets(id,workout_exercise_id,set_no,weight_kg,reps,updated_at) VALUES(?,?,?,?,?,?)`,id(),wid,n,rec.weight,rec.reps,t);
  }
  return sid;
}

function AppInner(){
  const db=useSQLiteContext();
  const [ready,setReady]=useState(false); const [onboard,setOnboard]=useState(false); const [lang,setLang]=useState<Lang>('en'); const [units,setUnits]=useState<'kg'|'lb'>('kg'); const [goal,setGoal]=useState('balanced'); const [days,setDays]=useState(3); const [tab,setTab]=useState<Tab>('home'); const [activeId,setActiveId]=useState<string|null>(null); const [tick,setTick]=useState(0);
  const rtl=lang==='ar', w=words[lang];
  const refresh=useCallback(async()=>{const s=await db.getFirstAsync<any>(`SELECT * FROM settings WHERE id=1`);setOnboard(!!s?.onboarding);setLang((s?.lang??'en') as Lang);setUnits((s?.units??'kg') as any);setGoal(s?.goal??'balanced');setDays(s?.days??3);const a=await db.getFirstAsync<any>(`SELECT id FROM sessions WHERE status='active' ORDER BY started_at DESC LIMIT 1`);setActiveId(a?.id??null);setReady(true);setTick(x=>x+1);},[db]);
  useEffect(()=>{refresh();},[refresh]);
  if(!ready)return <Center text="Loading…"/>;
  if(!onboard)return <Onboarding db={db} lang={lang} setLang={setLang} onDone={refresh}/>;
  if(activeId)return <Workout db={db} sid={activeId} lang={lang} units={units} onExit={async()=>{setActiveId(null);await refresh();}}/>;
  return <SafeAreaView style={s.safe}><StatusBar style={Appearance.getColorScheme()==='dark'?'light':'dark'}/><View style={s.shell}><View style={{flex:1}}>{tab==='home'?<Home db={db} w={w} lang={lang} tick={tick} onRefresh={refresh} onStart={async()=>{try{setActiveId(await startWorkout(db));}catch{Alert.alert('Routine',w.noRoutine);}}}/>:tab==='routine'?<Routine db={db} w={w} lang={lang} goal={goal} days={days} onChanged={refresh}/>:tab==='history'?<History db={db} lang={lang} tick={tick}/>:tab==='progress'?<Progress db={db} lang={lang} tick={tick}/>:<Settings db={db} w={w} lang={lang} units={units} onLang={async(l)=>{await db.runAsync(`UPDATE settings SET lang=?,updated_at=? WHERE id=1`,l,now());setLang(l);}} onUnits={async(u)=>{await db.runAsync(`UPDATE settings SET units=?,updated_at=? WHERE id=1`,u,now());setUnits(u);}} onReset={async()=>{await db.execAsync(`DELETE FROM workout_sets;DELETE FROM workout_exercises;DELETE FROM sessions;DELETE FROM routine_exercises;DELETE FROM routine_days;UPDATE settings SET onboarding=0,updated_at='${now()}';`);refresh();}}/>}</View><Nav tab={tab} setTab={setTab} w={w}/></View></SafeAreaView>;
}

function Center({text}:{text:string}){return <SafeAreaView style={[s.safe,{justifyContent:'center',alignItems:'center'}]}><Text style={s.title}>{text}</Text></SafeAreaView>}
function Button({title,onPress,secondary=false,danger=false}:{title:string,onPress:()=>void,secondary?:boolean,danger?:boolean}){return <Pressable onPress={onPress} style={({pressed})=>[s.btn,secondary&&s.btn2,danger&&{backgroundColor:C.red},pressed&&{opacity:.75}]}><Text style={[s.btnText,secondary&&{color:C.ink}]}>{title}</Text></Pressable>}
function Card({children}:{children:any}){return <View style={s.card}>{children}</View>}
function Heading({children}:{children:any}){return <Text style={s.title}>{children}</Text>}
function Muted({children}:{children:any}){return <Text style={s.muted}>{children}</Text>}

function Onboarding({db,lang,setLang,onDone}:any){const [goal,setGoal]=useState('balanced'),[days,setDays]=useState(3),[units,setUnits]=useState('kg');const rtl=lang==='ar';return <SafeAreaView style={s.safe}><ScrollView contentContainerStyle={s.page}><Heading>{rtl?'برنامجك. أذكى.':'Your Program. Smarter.'}</Heading><Muted>{rtl?'احتفظ ببرنامجك، ودع التطبيق يحسب التقدم والتعديلات الضرورية فقط.':'Keep your program. Let the app calculate progression and change only what needs changing.'}</Muted><Card><Text style={s.label}>{rtl?'الهدف':'Goal'}</Text><Choice value={goal} setValue={setGoal} items={['strength','hypertrophy','balanced']}/><Text style={s.label}>{rtl?'أيام التدريب':'Days / week'}</Text><Choice value={String(days)} setValue={(x)=>setDays(Number(x))} items={['2','3','4']}/><Text style={s.label}>{rtl?'الوحدات':'Units'}</Text><Choice value={units} setValue={setUnits} items={['kg','lb']}/><Text style={s.label}>{rtl?'اللغة':'Language'}</Text><Choice value={lang} setValue={setLang} items={['en','ar']}/></Card><Button title={rtl?'ابدأ':'Continue'} onPress={async()=>{await db.runAsync(`UPDATE settings SET onboarding=1,goal=?,days=?,units=?,lang=?,updated_at=? WHERE id=1`,goal,days,units,lang,now());await createStarter(db,goal,days);onDone();}}/></ScrollView></SafeAreaView>}
function Choice({value,setValue,items}:any){return <View style={s.rowWrap}>{items.map((x:string)=><Pressable key={x} onPress={()=>setValue(x)} style={[s.pill,value===x&&s.pillOn]}><Text style={[s.pillText,value===x&&{color:'#fff'}]}>{x}</Text></Pressable>)}</View>}

function Home({db,w,lang,tick,onStart}:any){const [last,setLast]=useState<any>(null),[day,setDay]=useState<any>(null);useEffect(()=>{db.getFirstAsync(`SELECT * FROM sessions WHERE status='completed' ORDER BY ended_at DESC LIMIT 1`).then(setLast);db.getFirstAsync(`SELECT * FROM routine_days ORDER BY position LIMIT 1`).then(setDay);},[db,tick]);return <ScrollView contentContainerStyle={s.page}><Heading>{w.tag}</Heading><Muted>{lang==='ar'?'تسجيل سريع، تقدم قابل للتفسير، وتدخل بأقل قدر ممكن.':'Fast logging, explainable progression, minimal intervention.'}</Muted><Card><Text style={s.kicker}>{w.today}</Text><Text style={s.cardTitle}>{day?.name??w.noRoutine}</Text>{day&&<Button title={w.start} onPress={onStart}/>}</Card><Card><Text style={s.kicker}>{lang==='ar'?'آخر جلسة':'Last workout'}</Text>{last?<><Text style={s.cardTitle}>{last.name}</Text><Muted>{new Date(last.started_at).toLocaleString()}</Muted></>:<Muted>{lang==='ar'?'لا توجد جلسات مكتملة بعد.':'No completed workouts yet.'}</Muted>}</Card><Card><Text style={s.cardTitle}>{lang==='ar'?'كيف يعمل التقدم؟':'How progression works'}</Text><Muted>{lang==='ar'?'إذا بلغت أعلى نطاق التكرارات في جلستين متتاليتين مع جهد مناسب، يزيد الوزن بأصغر خطوة متاحة. جلسة سيئة واحدة لا تخفض الوزن.':'Two strong top-of-range exposures earn the smallest load increase. One bad day never triggers an aggressive reduction.'}</Muted></Card></ScrollView>}

function Routine({db,w,lang,goal,days,onChanged}:any){const [rows,setRows]=useState<any[]>([]);const load=useCallback(async()=>{const r=await db.getAllAsync<any>(`SELECT rd.name day,re.id,re.target_sets,re.rep_min,re.rep_max,re.priority,re.start_weight_kg,e.name FROM routine_days rd JOIN routine_exercises re ON re.day_id=rd.id JOIN exercises e ON e.id=re.exercise_id ORDER BY rd.position,re.position`);setRows(r);},[db]);useEffect(()=>{load();},[load]);const grouped=useMemo(()=>{const m=new Map<string,any[]>();for(const r of rows){if(!m.has(r.day))m.set(r.day,[]);m.get(r.day)!.push(r);}return [...m.entries()];},[rows]);return <ScrollView contentContainerStyle={s.page}><Heading>{w.routine}</Heading><Button secondary title={w.build} onPress={()=>Alert.alert(lang==='ar'?'إعادة البناء':'Rebuild routine',lang==='ar'?'سيستبدل البرنامج الحالي.':'This replaces the current local routine.',[{text:lang==='ar'?'إلغاء':'Cancel',style:'cancel'},{text:lang==='ar'?'متابعة':'Continue',onPress:async()=>{await createStarter(db,goal,days);load();onChanged();}}])}/>{grouped.map(([day,list])=><Card key={day}><Text style={s.cardTitle}>{day}</Text>{list.map((r:any)=><View key={r.id} style={s.line}><View style={{flex:1}}><Text style={s.label}>{r.name}</Text><Muted>{r.target_sets} × {r.rep_min}–{r.rep_max} · {r.priority}</Muted></View><TextInput style={s.smallInput} keyboardType="decimal-pad" defaultValue={String(r.start_weight_kg)} onEndEditing={async e=>{await db.runAsync(`UPDATE routine_exercises SET start_weight_kg=? WHERE id=?`,Number(e.nativeEvent.text)||0,r.id);load();}}/></View>)}</Card>)}</ScrollView>}

function Workout({db,sid,lang,units,onExit}:any){const w=words[lang as Lang];const [data,setData]=useState<any>({session:null,exercises:[]});const [clock,setClock]=useState(Date.now());const load=useCallback(async()=>{const session=await db.getFirstAsync<any>(`SELECT * FROM sessions WHERE id=?`,sid);const ex=await db.getAllAsync<any>(`SELECT we.*,e.name,e.sub_group,re.rest_sec,re.priority FROM workout_exercises we JOIN exercises e ON e.id=we.exercise_id LEFT JOIN routine_exercises re ON re.id=we.routine_exercise_id WHERE we.session_id=? AND we.skipped_reason IS NULL ORDER BY we.position`,sid);for(const x of ex)x.sets=await db.getAllAsync<any>(`SELECT * FROM workout_sets WHERE workout_exercise_id=? AND excluded_reason IS NULL ORDER BY set_no`,x.id);setData({session,exercises:ex});},[db,sid]);useEffect(()=>{load();},[load]);useEffect(()=>{const t=setInterval(()=>setClock(Date.now()),1000);return()=>clearInterval(t)},[]);const rest=Math.max(0,Math.ceil((Date.parse(data.session?.rest_end_at??'')-clock)/1000));const display=(kg:number)=>units==='lb'?(kg*2.2046226218).toFixed(1):kg.toFixed(1);const fromDisplay=(v:number)=>units==='lb'?v/2.2046226218:v;
  async function setPatch(set:any,field:string,value:any){await db.runAsync(`UPDATE workout_sets SET ${field}=?,updated_at=? WHERE id=?`,value,now(),set.id);load();}
  async function complete(set:any,restSec:number){const val=set.completed?0:1;await db.runAsync(`UPDATE workout_sets SET completed=?,updated_at=? WHERE id=?`,val,now(),set.id);if(val){const end=new Date(Date.now()+restSec*1000).toISOString();await db.runAsync(`UPDATE sessions SET rest_end_at=?,updated_at=? WHERE id=?`,end,now(),sid);}load();}
  async function busy(ex:any){const alt=await db.getFirstAsync<any>(`SELECT * FROM exercises WHERE sub_group=? AND id<>? LIMIT 1`,ex.sub_group,ex.exercise_id);if(!alt){Alert.alert('Busy','No close substitute found.');return;}Alert.alert(w.busy,`${ex.name} → ${alt.name}`,[{text:lang==='ar'?'اليوم فقط':'Just today',onPress:async()=>{await db.runAsync(`UPDATE workout_exercises SET substitution_for=exercise_id,exercise_id=? WHERE id=?`,alt.id,ex.id);load();}},{text:lang==='ar'?'استبدال دائم':'Replace permanently',onPress:async()=>{await db.runAsync(`UPDATE workout_exercises SET substitution_for=exercise_id,exercise_id=? WHERE id=?`,alt.id,ex.id);if(ex.routine_exercise_id)await db.runAsync(`UPDATE routine_exercises SET exercise_id=? WHERE id=?`,alt.id,ex.routine_exercise_id);load();}},{text:lang==='ar'?'إلغاء':'Cancel',style:'cancel'}]);}
  async function compress(){const optional=data.exercises.filter((x:any)=>x.priority==='optional');if(!optional.length){Alert.alert(w.less,lang==='ar'?'لا يوجد تمرين اختياري يمكن حذفه أولًا.':'No optional exercise needs removal first.');return;}Alert.alert(w.less,lang==='ar'?`سيُحذف ${optional.map((x:any)=>x.name).join('، ')} من هذه الجلسة فقط.`:`Remove ${optional.map((x:any)=>x.name).join(', ')} from this session only.`,[{text:lang==='ar'?'طبّق':'Apply',onPress:async()=>{for(const x of optional){await db.runAsync(`UPDATE workout_exercises SET skipped_reason='time_compression' WHERE id=?`,x.id);await db.runAsync(`UPDATE workout_sets SET excluded_reason='time_compression' WHERE workout_exercise_id=? AND completed=0`,x.id);}load();}},{text:lang==='ar'?'إلغاء':'Cancel',style:'cancel'}]);}
  async function finish(difficulty:string){await db.runAsync(`UPDATE sessions SET status='completed',difficulty=?,ended_at=?,updated_at=? WHERE id=?`,difficulty,now(),now(),sid);onExit();}
  return <SafeAreaView style={s.safe}><ScrollView contentContainerStyle={s.page}><Heading>{data.session?.name??'Workout'}</Heading>{rest>0&&<Card><Text style={s.timer}>{w.rest} {Math.floor(rest/60)}:{String(rest%60).padStart(2,'0')}</Text></Card>}<Button secondary title={`${w.less} · 20 min`} onPress={compress}/>{data.exercises.map((ex:any)=><Card key={ex.id}><Text style={s.cardTitle}>{ex.name}</Text><Muted>{ex.reason}</Muted><View style={s.row}><Pressable onPress={()=>Alert.alert(w.why,ex.reason)}><Text style={s.link}>{w.why}</Text></Pressable><Pressable onPress={()=>busy(ex)}><Text style={s.link}>{w.busy}</Text></Pressable></View><View style={s.tableHead}><Text style={s.cellMini}>#</Text><Text style={s.cell}>{w.weight} {units}</Text><Text style={s.cell}>{w.reps}</Text><Text style={s.cellMini}>RIR</Text><Text style={s.cellMini}>✓</Text></View>{ex.sets.map((set:any)=><View style={s.setRow} key={set.id}><Text style={s.cellMini}>{set.set_no}</Text><TextInput style={s.cellInput} keyboardType="decimal-pad" defaultValue={display(set.weight_kg)} onEndEditing={e=>setPatch(set,'weight_kg',fromDisplay(Number(e.nativeEvent.text)||0))}/><TextInput style={s.cellInput} keyboardType="number-pad" defaultValue={String(set.reps)} onEndEditing={e=>setPatch(set,'reps',Number(e.nativeEvent.text)||0)}/><TextInput style={s.miniInput} keyboardType="decimal-pad" defaultValue={set.rir==null?'':String(set.rir)} placeholder="—" onEndEditing={e=>setPatch(set,'rir',e.nativeEvent.text===''?null:Number(e.nativeEvent.text))}/><Pressable onPress={()=>complete(set,ex.rest_sec??120)} style={[s.check,set.completed&&{backgroundColor:C.green,borderColor:C.green}]}><Text style={{color:set.completed?'white':C.ink,fontWeight:'900'}}>{set.completed?'✓':'○'}</Text></Pressable></View>)}</Card>)}<Card><Text style={s.cardTitle}>{lang==='ar'?'كيف كانت الجلسة؟':'How did the session feel?'}</Text><View style={s.row}><Button secondary title={w.easy} onPress={()=>finish('easy')}/><Button secondary title={w.good} onPress={()=>finish('good')}/><Button secondary title={w.hard} onPress={()=>finish('hard')}/></View></Card></ScrollView></SafeAreaView>}

function History({db,lang,tick}:any){const [items,setItems]=useState<any[]>([]);useEffect(()=>{db.getAllAsync<any>(`SELECT s.*,COUNT(DISTINCT we.id) ex_count,SUM(CASE WHEN ws.completed=1 THEN 1 ELSE 0 END) set_count FROM sessions s LEFT JOIN workout_exercises we ON we.session_id=s.id LEFT JOIN workout_sets ws ON ws.workout_exercise_id=we.id WHERE s.status='completed' GROUP BY s.id ORDER BY s.started_at DESC`).then(setItems)},[db,tick]);return <ScrollView contentContainerStyle={s.page}><Heading>{words[lang as Lang].history}</Heading>{items.length===0&&<Muted>{lang==='ar'?'لا توجد جلسات مكتملة بعد.':'No completed workouts yet.'}</Muted>}{items.map(x=><Card key={x.id}><Text style={s.cardTitle}>{x.name}</Text><Muted>{new Date(x.started_at).toLocaleString()} · {x.ex_count} exercises · {x.set_count??0} sets</Muted></Card>)}</ScrollView>}
function Progress({db,lang,tick}:any){const [items,setItems]=useState<any[]>([]);useEffect(()=>{db.getAllAsync<any>(`SELECT e.name,MAX(ws.weight_kg) max_weight,MAX(ws.weight_kg*(1+ws.reps/30.0)) max_e1rm FROM workout_sets ws JOIN workout_exercises we ON we.id=ws.workout_exercise_id JOIN exercises e ON e.id=we.exercise_id WHERE ws.completed=1 AND ws.excluded_reason IS NULL GROUP BY e.id ORDER BY max_e1rm DESC`).then(setItems)},[db,tick]);return <ScrollView contentContainerStyle={s.page}><Heading>{words[lang as Lang].progress}</Heading><Muted>{lang==='ar'?'الـ 1RM التقديري يستخدم معادلة Epley وهو تقدير فقط.':'Estimated 1RM uses the Epley formula and is an estimate.'}</Muted>{items.map((x,i)=><Card key={i}><Text style={s.cardTitle}>{x.name}</Text><View style={s.between}><Text>Best {Number(x.max_weight).toFixed(1)} kg</Text><Text>e1RM {Number(x.max_e1rm).toFixed(1)} kg</Text></View></Card>)}</ScrollView>}
function Settings({db,w,lang,units,onLang,onUnits,onReset}:any){return <ScrollView contentContainerStyle={s.page}><Heading>{w.settings}</Heading><Card><Text style={s.label}>{w.language}</Text><Choice value={lang} setValue={onLang} items={['en','ar']}/><Text style={s.label}>{w.units}</Text><Choice value={units} setValue={onUnits} items={['kg','lb']}/></Card><Card><Text style={s.cardTitle}>Offline-first</Text><Muted>{lang==='ar'?'تسجيل المجموعات وحالة الجلسة محفوظان في SQLite على الهاتف فورًا ولا ينتظران خادمًا.':'Sets and active-workout state are written immediately to on-device SQLite and never wait for a server.'}</Muted></Card><Button danger title={w.reset} onPress={()=>Alert.alert(w.reset,'This clears local workout data.',[{text:'Cancel',style:'cancel'},{text:'Reset',style:'destructive',onPress:onReset}])}/></ScrollView>}
function Nav({tab,setTab,w}:any){const items:[Tab,string][]=[['home',w.home],['routine',w.routine],['history',w.history],['progress',w.progress],['settings',w.settings]];return <View style={s.nav}>{items.map(([key,label])=><Pressable key={key} onPress={()=>setTab(key)} style={s.navItem}><Text style={[s.navText,tab===key&&{color:C.blue,fontWeight:'900'}]}>{label}</Text></Pressable>)}</View>}

const s=StyleSheet.create({safe:{flex:1,backgroundColor:C.bg},shell:{flex:1},page:{padding:18,paddingBottom:34,gap:14},card:{backgroundColor:C.card,borderWidth:1,borderColor:C.line,borderRadius:20,padding:16,gap:10},title:{fontSize:29,fontWeight:'900',color:C.ink},cardTitle:{fontSize:18,fontWeight:'850',color:C.ink},label:{fontSize:15,fontWeight:'800',color:C.ink},kicker:{fontSize:12,fontWeight:'800',textTransform:'uppercase',letterSpacing:.7,color:C.muted},muted:{fontSize:13,lineHeight:19,color:C.muted},btn:{minHeight:50,borderRadius:14,backgroundColor:C.blue,paddingHorizontal:15,alignItems:'center',justifyContent:'center',flexShrink:1},btn2:{backgroundColor:'#F2F4F7',borderWidth:1,borderColor:C.line},btnText:{fontSize:15,fontWeight:'850',color:'#fff'},row:{flexDirection:'row',gap:10,alignItems:'center',flexWrap:'wrap'},rowWrap:{flexDirection:'row',gap:8,flexWrap:'wrap'},pill:{paddingVertical:10,paddingHorizontal:13,borderRadius:99,backgroundColor:'#F2F4F7'},pillOn:{backgroundColor:C.blue},pillText:{fontWeight:'750',color:C.ink},line:{flexDirection:'row',alignItems:'center',borderTopWidth:1,borderColor:C.line,paddingTop:10,gap:10},smallInput:{width:70,minHeight:42,borderWidth:1,borderColor:C.line,borderRadius:10,textAlign:'center'},link:{fontWeight:'850',color:C.blue},timer:{fontSize:27,fontWeight:'900',textAlign:'center',color:C.blue},tableHead:{flexDirection:'row',gap:7,alignItems:'center'},setRow:{flexDirection:'row',gap:7,alignItems:'center'},cell:{flex:1,textAlign:'center',fontSize:12,fontWeight:'800',color:C.muted},cellMini:{width:38,textAlign:'center',fontSize:12,fontWeight:'800',color:C.muted},cellInput:{flex:1,minHeight:43,borderWidth:1,borderColor:C.line,borderRadius:9,textAlign:'center',color:C.ink},miniInput:{width:45,minHeight:43,borderWidth:1,borderColor:C.line,borderRadius:9,textAlign:'center',color:C.ink},check:{width:42,height:42,borderWidth:1,borderColor:C.line,borderRadius:10,alignItems:'center',justifyContent:'center'},between:{flexDirection:'row',justifyContent:'space-between'},nav:{minHeight:68,borderTopWidth:1,borderColor:C.line,backgroundColor:C.card,flexDirection:'row',paddingBottom:6},navItem:{flex:1,alignItems:'center',justifyContent:'center',paddingHorizontal:3},navText:{fontSize:11,fontWeight:'700',color:C.muted,textAlign:'center'}});

export default function App(){return <SafeAreaProvider><SQLiteProvider databaseName="program-smarter.db" onInit={migrate}><AppInner/></SQLiteProvider></SafeAreaProvider>}
