package com.acrps.podcastforge.audio;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import android.os.StatFs;
import com.audx.android.Audx;
import java.io.*;
import java.nio.*;
import java.util.*;
import kotlin.Unit;

public final class PodcastProcessor {
    public static final class Options { public boolean denoise=true,deesser=true,leveler=true,master=true; public int preset=0; }
    public interface Progress { void onProgress(int percent,String message); }
    private static final double EPS=1e-12;

    public static void process(Context ctx,Uri uri,OutputStream out,Options opt,Progress cb)throws Exception{
        File raw=File.createTempFile("pf6_raw_",".pcm",ctx.getCacheDir());
        File clean=File.createTempFile("pf6_clean_",".pcm",ctx.getCacheDir());
        File restored=File.createTempFile("pf6_restore_",".f32",ctx.getCacheDir());
        File shaped=File.createTempFile("pf6_shape_",".f32",ctx.getCacheDir());
        try{
            DecodeInfo info=decode(ctx,uri,raw,cb);if(info.samples<=0)throw new IOException("لم يُفك أي صوت صالح من الملف");
            long need=info.samples*12L+64L*1024*1024;StatFs fs=new StatFs(ctx.getCacheDir().getPath());if(fs.getAvailableBytes()<need)throw new IOException("المساحة المؤقتة غير كافية");
            Analysis a=analyze(raw,info,cb);denoise(raw,clean,info,a,opt,cb);
            SpectralReconstructor.process(raw,clean,restored,info.samples,info.sampleRate,a.denoiseStrength,a::speechAt,cb);
            MasteringEngine.shape(restored,shaped,info.samples,info.sampleRate,a,opt,cb);
            MasteringEngine.LoudnessResult l=MasteringEngine.measure(shaped,info.samples,info.sampleRate,cb);
            MasteringEngine.renderFinal(shaped,out,info.samples,info.sampleRate,l,cb);out.flush();cb.onProgress(100,"اكتملت الهندسة البودكاستية");
        }finally{del(raw);del(clean);del(restored);del(shaped);}
    }

    private static Analysis analyze(File raw,DecodeInfo info,Progress cb)throws Exception{
        int sr=info.sampleRate,block=Math.max(256,sr/10);short[] b=new short[block];ArrayList<Float> levels=new ArrayList<>();double dc=0;long total=0,clipped=0;
        try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(raw),1<<20)){int n;while((n=readShorts(in,b))>0){double e=0;for(int i=0;i<n;i++){double v=b[i]/32768.0;e+=v*v;dc+=v;if(Math.abs(v)>.985)clipped++;}levels.add((float)db(Math.sqrt(e/Math.max(1,n))));total+=n;if(total%(sr*20L)<n)cb.onProgress(18+(int)(7.0*total/Math.max(1,info.samples)),"تحليل الكلام والضوضاء...");}}
        float[] sorted=new float[levels.size()];for(int i=0;i<sorted.length;i++)sorted[i]=levels.get(i);Arrays.sort(sorted);double noise=pct(sorted,.12),threshold=clamp(noise+9.5,-50,-29);
        float[] speech=new float[levels.size()];ArrayList<Float> active=new ArrayList<>();for(int i=0;i<speech.length;i++){double p=1/(1+Math.exp(-(levels.get(i)-threshold)/2.8));speech[i]=(float)p;if(p>.58)active.add(levels.get(i));}
        float[] as=new float[active.size()];for(int i=0;i<as.length;i++)as[i]=active.get(i);Arrays.sort(as);double median=as.length>0?pct(as,.5):pct(sorted,.75),snr=clamp(median-noise,2,45);
        Analysis a=new Analysis();a.blockSamples=block;a.speechProbability=speech;a.riderDb=buildRider(levels,speech);a.snrDb=snr;a.denoiseStrength=clamp(.62+(16-snr)*.018,.52,.88);a.dcOffset=dc/Math.max(1,total);a.clippedFraction=clipped/(double)Math.max(1,total);
        profile(raw,info,a,cb);return a;
    }

    private static double[] buildRider(ArrayList<Float> levels,float[] speech){int n=levels.size();double[] f=new double[n],r=new double[n],o=new double[n];double g=0;for(int i=0;i<n;i++){double w=speech[i]>.45?clamp(-19.5-levels.get(i),-4.5,4.5):Math.min(0,g),k=w<g?.36:(speech[i]>.45?.16:.045);g+=k*(w-g);f[i]=g;}g=0;for(int i=n-1;i>=0;i--){double w=speech[i]>.45?clamp(-19.5-levels.get(i),-4.5,4.5):Math.min(0,g),k=w<g?.30:(speech[i]>.45?.13:.04);g+=k*(w-g);r[i]=g;}for(int i=0;i<n;i++)o[i]=.58*f[i]+.42*r[i];return o;}

    private static void profile(File raw,DecodeInfo info,Analysis a,Progress cb)throws Exception{
        int sr=info.sampleRate,block=a.blockSamples;short[] b=new short[block];double h50=0,n50=0,h60=0,n60=0;int bi=0;BandMeter[] bands={new BandMeter(sr,70,180),new BandMeter(sr,180,360),new BandMeter(sr,360,760),new BandMeter(sr,760,1600),new BandMeter(sr,1600,4500),new BandMeter(sr,4500,Math.min(9500,sr*.45)),new BandMeter(sr,9500,Math.min(16000,sr*.47))};
        try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(raw),1<<20)){int n;while((n=readShorts(in,b))>0){double p=a.speechProbability[Math.min(bi,a.speechProbability.length-1)];if(p>.58){for(int i=0;i<n;i++){float x=b[i]/32768f;for(BandMeter m:bands)m.add(x);}}else if(p<.25){h50+=goertzel(b,n,sr,50)+.55*goertzel(b,n,sr,100);n50+=.5*(goertzel(b,n,sr,45)+goertzel(b,n,sr,55));h60+=goertzel(b,n,sr,60)+.55*goertzel(b,n,sr,120);n60+=.5*(goertzel(b,n,sr,54)+goertzel(b,n,sr,66));}bi++;}}
        for(int i=0;i<bands.length;i++)a.bandDb[i]=bands[i].value();a.hum50=h50>Math.max(EPS,n50*2.6);a.hum60=!a.hum50&&h60>Math.max(EPS,n60*2.6);double mid=(a.bandDb[3]+a.bandDb[4])*.5;a.warmthDb=clamp((-5.5-(a.bandDb[0]-mid))*.12,-.25,1.1);a.presenceDb=clamp(.75+(-6-(a.bandDb[4]-mid))*.10,.45,1.45);a.airDb=a.snrDb>17&&a.bandDb[5]>a.bandDb[6]-15?.25:0;cb.onProgress(31,"اكتمل ملف نبرة المتحدث");
    }

    private static void denoise(File raw,File output,DecodeInfo info,Analysis a,Options opt,Progress cb)throws Exception{
        int sr=info.sampleRate,frame=Math.max(80,sr/100);short[] input=new short[frame],repaired=new short[frame],cleaned=new short[frame];Repair repair=new Repair();Audx audx=null;
        try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(raw),1<<20);BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(output),1<<20)){
            if(opt.denoise)audx=new Audx.Builder().inputRate(sr).resampleQuality(Audx.AUDX_RESAMPLER_QUALITY_MAX).build();long done=0;int n;
            while((n=readShorts(in,input))>0){if(n<frame)Arrays.fill(input,n,frame,(short)0);repair.run(input,repaired,n);if(audx!=null)audx.process(repaired,cleaned,p->Unit.INSTANCE);else System.arraycopy(repaired,0,cleaned,0,frame);Quality q=quality(repaired,cleaned,n);boolean unsafe=!Double.isFinite(q.clean)||q.clean>Math.max(.10,q.dry*3.3)||(q.dry>.012&&q.clean<q.dry*.025)||q.corr<-.15;writeShorts(out,unsafe?repaired:cleaned,n);done+=n;if(done%(sr*7L)<n)cb.onProgress(31+(int)(7.0*done/Math.max(1,info.samples)),"التنقية العصبية المحافظة: "+(int)(100.0*done/Math.max(1,info.samples))+"%");}
        }finally{if(audx!=null)audx.close();}
    }

    private static DecodeInfo decode(Context ctx,Uri uri,File raw,Progress cb)throws Exception{
        MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;try(BufferedOutputStream pcm=new BufferedOutputStream(new FileOutputStream(raw),1<<20)){
            ex.setDataSource(ctx,uri,null);int track=-1;MediaFormat fmt=null;for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String m=f.getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("audio/")){track=i;fmt=f;break;}}if(track<0||fmt==null)throw new IOException("لا يوجد مسار صوتي صالح");ex.selectTrack(track);codec=MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));codec.configure(fmt,null,null,0);codec.start();int sr=fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)?fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE):48000,ch=fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;long dur=fmt.containsKey(MediaFormat.KEY_DURATION)?fmt.getLong(MediaFormat.KEY_DURATION):1,samples=0;MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();boolean id=false,od=false;
            while(!od){if(!id){int ix=codec.dequeueInputBuffer(10000);if(ix>=0){ByteBuffer b=codec.getInputBuffer(ix);if(b==null)continue;b.clear();int z=ex.readSampleData(b,0);if(z<0){codec.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);id=true;}else{codec.queueInputBuffer(ix,0,z,ex.getSampleTime(),0);ex.advance();}}}int ox=codec.dequeueOutputBuffer(bi,10000);if(ox==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat of=codec.getOutputFormat();if(of.containsKey(MediaFormat.KEY_SAMPLE_RATE))sr=of.getInteger(MediaFormat.KEY_SAMPLE_RATE);if(of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))ch=of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);}else if(ox>=0){ByteBuffer b=codec.getOutputBuffer(ox);if(b!=null&&bi.size>0){b.position(bi.offset);b.limit(bi.offset+bi.size);b.order(ByteOrder.LITTLE_ENDIAN);int frames=(bi.size/2)/Math.max(1,ch);for(int f=0;f<frames;f++){long s=0;for(int c=0;c<ch;c++)s+=b.getShort();short mono=(short)(s/ch);pcm.write(mono&255);pcm.write((mono>>>8)&255);}samples+=frames;}if((bi.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)od=true;codec.releaseOutputBuffer(ox,false);cb.onProgress((int)Math.min(17,17.0*Math.max(0,bi.presentationTimeUs)/Math.max(1,dur)),"فك التسجيل الطويل...");}}
            DecodeInfo d=new DecodeInfo();d.sampleRate=sr;d.samples=samples;return d;
        }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}try{ex.release();}catch(Exception ignored){}}
    }

    private static Quality quality(short[] d,short[] c,int n){double sd=0,sc=0,dot=0;for(int i=0;i<n;i++){double x=d[i]/32768.0,y=c[i]/32768.0;sd+=x*x;sc+=y*y;dot+=x*y;}Quality q=new Quality();q.dry=Math.sqrt(sd/Math.max(1,n));q.clean=Math.sqrt(sc/Math.max(1,n));q.corr=dot/Math.sqrt(Math.max(EPS,sd*sc));return q;}
    private static int readShorts(InputStream in,short[] b)throws IOException{int i=0;while(i<b.length){int lo=in.read();if(lo<0)break;int hi=in.read();if(hi<0)break;b[i++]=(short)((hi<<8)|lo);}return i;}
    private static void writeShorts(OutputStream out,short[] b,int n)throws IOException{byte[] z=new byte[n*2];for(int i=0;i<n;i++){z[i*2]=(byte)b[i];z[i*2+1]=(byte)(b[i]>>>8);}out.write(z);}
    private static double pct(float[] a,double p){if(a.length==0)return-40;double x=clamp(p,0,1)*(a.length-1);int i=(int)x,j=Math.min(a.length-1,i+1);return a[i]+(a[j]-a[i])*(x-i);}
    private static double db(double x){return 20*Math.log10(x+1e-9);}private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}private static void del(File f){if(f!=null&&!f.delete())f.deleteOnExit();}
    private static double goertzel(short[] x,int n,int sr,double hz){double w=2*Math.PI*hz/sr,c=2*Math.cos(w),s0=0,s1=0,s2=0;for(int i=0;i<n;i++){s0=x[i]/32768.0+c*s1-s2;s2=s1;s1=s0;}return s1*s1+s2*s2-c*s1*s2;}

    public static final class Analysis { int blockSamples;float[] speechProbability;double[] riderDb;double snrDb,denoiseStrength,dcOffset,clippedFraction,warmthDb,presenceDb,airDb;boolean hum50,hum60;double[] bandDb=new double[7];double speechAt(long i){return speechProbability[Math.min(speechProbability.length-1,(int)(i/Math.max(1,blockSamples)))];}double riderAt(long i){return riderDb[Math.min(riderDb.length-1,(int)(i/Math.max(1,blockSamples)))];} }
    private static final class DecodeInfo{int sampleRate;long samples;}private static final class Quality{double dry,clean,corr;}
    private static final class Repair{short prev;void run(short[] in,short[] out,int n){for(int i=0;i<n;i++){int v=in[i],p=prev;if(i>0)p=in[i-1];int nx=i+1<n?in[i+1]:v;if(Math.abs(v-p)>11000&&Math.abs(v-nx)>11000&&Math.abs(p-nx)<6500)v=(p+nx)/2;out[i]=(short)v;}if(n>0)prev=out[n-1];}}
    private static final class BandMeter{final Biquad hp,lp;double sum;long n;BandMeter(int sr,double lo,double hi){hp=Biquad.highPass(sr,Math.max(20,lo),.707);lp=Biquad.lowPass(sr,Math.min(sr*.47,hi),.707);}void add(float x){float y=lp.run(hp.run(x));sum+=y*y;n++;}double value(){return 10*Math.log10(sum/Math.max(1,n)+1e-12);}}
}
