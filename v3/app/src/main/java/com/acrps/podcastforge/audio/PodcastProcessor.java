package com.acrps.podcastforge.audio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import com.audx.android.Audx;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import kotlin.Unit;

public final class PodcastProcessor {
    public static final class Options { public boolean denoise=true,deesser=true,leveler=true,master=true; public int preset=0; }
    public interface Progress { void onProgress(int percent,String message); }
    private static final int IO_SAMPLES=32768;

    public static void process(Context ctx,Uri uri,OutputStream out,Options opt,Progress cb)throws Exception{
        File raw=File.createTempFile("pf4_raw_",".pcm",ctx.getCacheDir());
        File mastered=File.createTempFile("pf4_master_",".pcm",ctx.getCacheDir());
        try{
            DecodeInfo info=decodeToMonoFile(ctx,uri,raw,cb);
            if(info.samples<=0)throw new Exception("لم يُفك أي صوت صالح من الملف");
            Analysis source=analyze(raw,info.sampleRate,cb);
            RenderStats rendered=processToFile(raw,mastered,info,source,opt,cb);
            cb.onProgress(91,"ضبط مستوى البودكاست والذروة النهائية...");
            double rms=Math.sqrt(rendered.sumSq/Math.max(1,rendered.samples));
            double targetRms=Math.pow(10.0,(opt.preset==1?-17.8:-17.0)/20.0);
            double gain=targetRms/Math.max(1e-9,rms);
            double ceiling=0.89125094;
            if(rendered.peak*gain>ceiling)gain=ceiling/Math.max(1e-9,rendered.peak);
            gain=Math.max(0.55,Math.min(3.2,gain));
            WavWriter.writeHeader(out,rendered.samples,info.sampleRate,1);
            renderFinal(mastered,out,rendered.samples,gain,ceiling,info.sampleRate,cb);
            out.flush();cb.onProgress(100,"اكتملت التنقية والماستر البودكاستي");
        }finally{
            if(!raw.delete())raw.deleteOnExit();
            if(!mastered.delete())mastered.deleteOnExit();
        }
    }

    private static Analysis analyze(File raw,int sr,Progress cb)throws Exception{
        Analysis a=new Analysis();long total=Math.max(1,raw.length()/2);short[] b=new short[IO_SAMPLES];
        try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(raw),1<<20)){
            long done=0;int n;while((n=readShorts(in,b))>0){double frameSum=0;for(int i=0;i<n;i++){double v=b[i]/32768.0;a.peak=Math.max(a.peak,Math.abs(v));frameSum+=v*v;}double frameRms=Math.sqrt(frameSum/Math.max(1,n));if(frameRms>0.0035){a.activeSum+=frameSum;a.activeCount+=n;}done+=n;if(done%(sr*10L)<n)cb.onProgress(29+(int)(10.0*done/total),"تحليل الكلام والمستوى الديناميكي...");}
        }
        a.activeRms=Math.sqrt(a.activeSum/Math.max(1,a.activeCount));if(a.activeRms<1e-6)a.activeRms=0.03;return a;
    }

    private static RenderStats processToFile(File raw,File processed,DecodeInfo info,Analysis a,Options opt,Progress cb)throws Exception{
        int sr=info.sampleRate;
        Biquad hp=Biquad.highPass(sr,opt.preset==1?78:66,0.707);
        Biquad hum50=Biquad.notch(sr,50,12.0),hum100=Biquad.notch(sr,100,10.0);
        Biquad warmth=Biquad.lowShelf(sr,145,opt.preset==2?1.8:1.15);
        Biquad mud=Biquad.peak(sr,255,0.85,opt.preset==1?-2.4:-1.8);
        Biquad box=Biquad.peak(sr,510,1.0,-0.85);
        Biquad nasal=Biquad.peak(sr,980,1.15,-1.0);
        Biquad presence=Biquad.peak(sr,3050,0.95,opt.preset==3?2.4:1.75);
        Biquad air=Biquad.highShelf(sr,8200,opt.preset==3?1.4:0.75);
        Biquad essLP=Biquad.lowPass(sr,5200,0.707);

        double inputDb=20*Math.log10(a.activeRms+1e-9);
        double desiredInputDb=opt.preset==1?-21.5:-20.8;
        double trimDb=Math.max(-4.0,Math.min(8.0,desiredInputDb-inputDb));
        double inputTrim=Math.pow(10,trimDb/20.0);

        double env=0,gain=1,essEnv=0,dryMixSmooth=0.02;
        double compAttack=Math.exp(-1.0/(0.020*sr)),compRelease=Math.exp(-1.0/(0.300*sr));
        double gainAttack=Math.exp(-1.0/(0.012*sr)),gainRelease=Math.exp(-1.0/(0.420*sr));
        double essAttack=Math.exp(-1.0/(0.0035*sr)),essRelease=Math.exp(-1.0/(0.110*sr));
        double thresholdDb=opt.preset==1?-23.0:-22.0,ratio=opt.preset==1?2.45:2.25;
        double makeup=Math.pow(10,(opt.preset==2?1.8:1.35)/20.0);
        double prev=0,prevDry=0;
        RenderStats stats=new RenderStats();
        Audx audx=null;
        try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(raw),1<<20);
            BufferedOutputStream pcm=new BufferedOutputStream(new FileOutputStream(processed),1<<20)){
            int frame=Math.max(80,sr/100);short[] input=new short[frame],cleaned=new short[frame];
            audx=opt.denoise?new Audx.Builder().inputRate(sr).resampleQuality(Audx.AUDX_RESAMPLER_QUALITY_MAX).build():null;
            long done=0,total=Math.max(1,info.samples);byte[] outBytes=new byte[frame*2];
            while(true){
                int valid=readShorts(in,input);if(valid<=0)break;
                if(valid<frame)for(int i=valid;i<frame;i++)input[i]=0;
                final float[] vad={1f};
                if(audx!=null)audx.process(input,cleaned,p->{vad[0]=p;return Unit.INSTANCE;});else System.arraycopy(input,0,cleaned,0,frame);
                double desiredDry=vad[0]>0.72f?0.045:(vad[0]>0.35f?0.025:0.0);
                for(int i=0;i<valid;i++){
                    dryMixSmooth+=(desiredDry-dryMixSmooth)*(desiredDry>dryMixSmooth?0.0012:0.0045);
                    double dry=input[i]/32768.0,den=cleaned[i]/32768.0;
                    double s=opt.denoise?(den*(1.0-dryMixSmooth)+dry*dryMixSmooth):dry;
                    double delta=s-prev,rawDelta=dry-prevDry;
                    if(Math.abs(delta)>0.16&&Math.abs(rawDelta)<Math.abs(delta)*0.42)s=prev+Math.copySign(0.16,delta);
                    s=0.985*s+0.015*prev;prev=s;prevDry=dry;
                    float x=(float)(s*inputTrim);
                    x=hp.run(x);x=hum50.run(x);x=hum100.run(x);x=warmth.run(x);x=mud.run(x);x=box.run(x);x=nasal.run(x);x=presence.run(x);x=air.run(x);
                    if(opt.deesser){float low=essLP.run(x);double hi=x-low,ah=Math.abs(hi),k=ah>essEnv?essAttack:essRelease;essEnv=k*essEnv+(1-k)*ah;double essDb=20*Math.log10(essEnv+1e-9);double reduction=essDb>-27.0?Math.min(0.22,(essDb+27.0)/11.0*0.22):0;x=(float)(low+hi*(1.0-reduction));}
                    double abs=Math.abs(x),ck=abs>env?compAttack:compRelease;env=ck*env+(1-ck)*abs;
                    double y=x;
                    if(opt.leveler){double levelDb=20*Math.log10(env+1e-9),grDb=levelDb>thresholdDb?-(levelDb-thresholdDb)*(1.0-1.0/ratio):0;double target=Math.pow(10,grDb/20.0)*makeup;double gk=target<gain?gainAttack:gainRelease;gain=gk*gain+(1-gk)*target;double compressed=x*gain;y=0.30*x+0.70*compressed;}
                    if(opt.master){double excited=Math.tanh(y*1.025)/Math.tanh(1.025);y=0.92*y+0.08*excited;}
                    y=Math.max(-0.97,Math.min(0.97,y));
                    short q=(short)Math.round(y*32767.0);outBytes[i*2]=(byte)(q&255);outBytes[i*2+1]=(byte)((q>>>8)&255);
                    stats.sumSq+=y*y;stats.peak=Math.max(stats.peak,Math.abs(y));stats.samples++;
                }
                pcm.write(outBytes,0,valid*2);done+=valid;
                if(done%(sr*6L)<valid)cb.onProgress(40+(int)(49.0*done/total),"تنقية v2 المحسنة وهندسة البودكاست: "+(int)(100.0*done/total)+"%");
            }
        }finally{if(audx!=null)audx.close();}
        return stats;
    }

    private static void renderFinal(File processed,OutputStream out,long samples,double gain,double ceiling,int sr,Progress cb)throws Exception{
        WavWriter.Pcm16Sink sink=new WavWriter.Pcm16Sink(out);short[] b=new short[IO_SAMPLES];long done=0;double limiterGain=1.0;
        double attack=Math.exp(-1.0/(0.0015*sr)),release=Math.exp(-1.0/(0.080*sr));
        try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(processed),1<<20)){
            int n;while((n=readShorts(in,b))>0){for(int i=0;i<n;i++){double v=(b[i]/32768.0)*gain;double need=Math.abs(v)>ceiling?ceiling/Math.abs(v):1.0;double k=need<limiterGain?attack:release;limiterGain=k*limiterGain+(1-k)*need;v*=limiterGain;v=Math.max(-ceiling,Math.min(ceiling,v));sink.write((short)Math.round(v*32767.0));}done+=n;if(done%(sr*12L)<n)cb.onProgress(92+(int)(7.0*done/Math.max(1,samples)),"إخراج الماستر النهائي...");}
        }
        sink.flush();
    }

    private static DecodeInfo decodeToMonoFile(Context ctx,Uri uri,File raw,Progress cb)throws Exception{
        MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;
        try(BufferedOutputStream pcm=new BufferedOutputStream(new FileOutputStream(raw),1<<20)){
            ex.setDataSource(ctx,uri,null);int track=-1;MediaFormat fmt=null;
            for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String m=f.getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("audio/")){track=i;fmt=f;break;}}
            if(track<0||fmt==null)throw new Exception("لا يوجد مسار صوتي صالح");
            ex.selectTrack(track);String mime=fmt.getString(MediaFormat.KEY_MIME);codec=MediaCodec.createDecoderByType(mime);codec.configure(fmt,null,null,0);codec.start();
            int sr=fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)?fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE):48000;
            int ch=fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;
            long dur=fmt.containsKey(MediaFormat.KEY_DURATION)?fmt.getLong(MediaFormat.KEY_DURATION):1;
            MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();boolean inDone=false,outDone=false;long samples=0;
            while(!outDone){
                if(!inDone){int idx=codec.dequeueInputBuffer(10000);if(idx>=0){ByteBuffer b=codec.getInputBuffer(idx);if(b==null)continue;b.clear();int n=ex.readSampleData(b,0);if(n<0){codec.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inDone=true;}else{codec.queueInputBuffer(idx,0,n,ex.getSampleTime(),0);ex.advance();}}}
                int o=codec.dequeueOutputBuffer(bi,10000);
                if(o==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat of=codec.getOutputFormat();if(of.containsKey(MediaFormat.KEY_SAMPLE_RATE))sr=of.getInteger(MediaFormat.KEY_SAMPLE_RATE);if(of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))ch=of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);}
                else if(o>=0){ByteBuffer b=codec.getOutputBuffer(o);if(b!=null&&bi.size>0){b.position(bi.offset);b.limit(bi.offset+bi.size);b.order(ByteOrder.LITTLE_ENDIAN);int shorts=bi.size/2,frames=shorts/Math.max(1,ch);for(int f=0;f<frames;f++){long sum=0;for(int c=0;c<ch;c++)sum+=b.getShort();short mono=(short)(sum/ch);pcm.write(mono&255);pcm.write((mono>>>8)&255);}samples+=frames;}if((bi.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outDone=true;codec.releaseOutputBuffer(o,false);cb.onProgress((int)Math.min(28,28.0*Math.max(0,bi.presentationTimeUs)/Math.max(1,dur)),"فك التسجيل إلى معالجة متدفقة...");}
            }
            DecodeInfo d=new DecodeInfo();d.sampleRate=sr;d.samples=samples;return d;
        }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}try{ex.release();}catch(Exception ignored){}}
    }

    private static int readShorts(InputStream in,short[] out)throws IOException{int i=0;while(i<out.length){int lo=in.read();if(lo<0)break;int hi=in.read();if(hi<0)break;out[i++]=(short)((hi<<8)|lo);}return i;}
    private static final class DecodeInfo{int sampleRate;long samples;}
    private static final class Analysis{double peak,activeSum,activeRms;long activeCount;}
    private static final class RenderStats{double sumSq,peak;long samples;}
}
