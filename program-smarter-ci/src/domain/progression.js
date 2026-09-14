function roundToIncrement(value, increment){
  if(!increment||increment<=0)return value;
  return Math.round(value/increment)*increment;
}
function validWorkSets(exposure){
  if(!exposure||exposure.excluded)return [];
  return (exposure.sets||[]).filter(s=>s.completed&&s.type==='work'&&!s.excludedReason);
}
function exposureQuality(exposure,cfg){
  const sets=validWorkSets(exposure);
  if(sets.length<cfg.targetSets)return 'incomplete';
  const top=sets.slice(0,cfg.targetSets).every(s=>Number(s.reps)>=cfg.repMax && (s.rir==null||Number(s.rir)>=cfg.targetRir));
  if(top)return 'top';
  const below=sets.slice(0,cfg.targetSets).filter(s=>Number(s.reps)<cfg.repMin).length;
  if(below>=Math.ceil(cfg.targetSets/2))return 'fail';
  return 'middle';
}
function maxCompletedWeight(exposures,fallback){
  for(const e of exposures||[]){for(const s of validWorkSets(e)){if(Number.isFinite(Number(s.weightKg)))return Number(s.weightKg)}}
  return Number(fallback||0);
}
function bestReps(exposure,repMin){
  return validWorkSets(exposure).reduce((m,s)=>Math.max(m,Number(s.reps)||0),repMin-1);
}
function decideProgression(cfg, exposures, opts={}){
  const clean=(exposures||[]).filter(e=>!e.excluded).slice(0,3);
  const current=maxCompletedWeight(clean,cfg.startWeightKg||0);
  const q0=exposureQuality(clean[0],cfg),q1=exposureQuality(clean[1],cfg);
  const result={weightKg:current,targetReps:cfg.repMin,action:'hold',reasonCode:'hold_middle',reason:'Hold load and build reps inside the target range.',optional:false};
  if(opts.daysSinceLast!=null && opts.daysSinceLast>=28){
    result.weightKg=roundToIncrement(current*0.95,cfg.incrementKg);result.action='return_adjustment';result.reasonCode='return_gap';result.reason='Long training gap: optional 5% return adjustment.';result.optional=true;return result;
  }
  if(q0==='top'&&q1==='top'){
    result.weightKg=roundToIncrement(current+cfg.incrementKg,cfg.incrementKg);result.targetReps=cfg.repMin;result.action='increase';result.reasonCode='two_top';result.reason='Two valid exposures reached the top of the range with sufficient reserve.';return result;
  }
  if(q0==='fail'&&q1==='fail'){
    result.weightKg=Math.max(0,roundToIncrement(current-cfg.incrementKg,cfg.incrementKg));result.targetReps=cfg.repMin;result.action='reduce';result.reasonCode='two_fail';result.reason='Two consecutive valid exposures missed the minimum target; excluded work did not count.';result.optional=true;return result;
  }
  const best=bestReps(clean[0],cfg.repMin);
  result.targetReps=Math.min(cfg.repMax,Math.max(cfg.repMin,best+1));
  if(q0==='incomplete'){result.reasonCode='incomplete';result.reason='The latest valid exposure was incomplete, so load is held.'}
  return result;
}
function estimateEpley(weightKg,reps){return reps<=1?Number(weightKg):Number(weightKg)*(1+Number(reps)/30)}
function kgToLb(kg){return Number(kg)*2.2046226218}
function lbToKg(lb){return Number(lb)/2.2046226218}
function rankSubstitutes(current,candidates,equipment){
  const available=new Set(equipment||[]);
  return (candidates||[]).filter(x=>x.id!==current.id && (!available.size||available.has(x.equipment)||x.equipment==='Bodyweight')).map(x=>{
    let score=0;if(x.primary===current.primary)score+=50;if(x.pattern===current.pattern)score+=30;if(x.subGroup===current.subGroup)score+=20;if(x.equipment===current.equipment)score+=5;return {...x,score};
  }).sort((a,b)=>b.score-a.score||a.name.localeCompare(b.name));
}
function timeCompressionPlan(items,minutesLeft){
  let budget=Math.max(0,Number(minutesLeft))*60;
  const copy=items.map(x=>({...x,remainingSets:Number(x.remainingSets||0)}));
  const estimate=()=>copy.reduce((t,x)=>t+(x.skipped?0:x.remainingSets*(Number(x.restSec||90)+45)),0);
  const changes=[];
  if(estimate()<=budget)return{items:copy,changes,estimatedSeconds:estimate()};
  for(const priority of ['optional','normal']){
    for(const x of copy.filter(i=>i.priority===priority).sort((a,b)=>b.position-a.position)){
      while(x.remainingSets>1 && estimate()>budget){x.remainingSets--;changes.push({type:'set',exerciseId:x.id,reason:'time_compression'})}
      if(estimate()>budget && priority==='optional' && x.remainingSets>0){x.skipped=true;changes.push({type:'exercise',exerciseId:x.id,reason:'time_compression'})}
      if(estimate()<=budget)break;
    }
    if(estimate()<=budget)break;
  }
  return{items:copy,changes,estimatedSeconds:estimate()};
}
module.exports={roundToIncrement,validWorkSets,exposureQuality,decideProgression,estimateEpley,kgToLb,lbToKg,rankSubstitutes,timeCompressionPlan};
