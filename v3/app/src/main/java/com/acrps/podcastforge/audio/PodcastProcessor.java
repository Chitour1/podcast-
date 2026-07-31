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
        File stage=File.createTempFile("pf4_stage_",".pcm",ctx.getCacheDir());
        try{
            DecodeInfo info=decodeToMonoFile(ctx,uri,raw,cb);
            if(info.samples<=0)throw new Exception("لم يُفك أي صوت صالح من الملف");
            Analysis input=analyze(raw,info.sampleRate,28,36,"تحليل التسجيل الأصلي...",cb);
            cb.onProgress(37,"تنقية RNNoise بأساس النسخة الثانية...");
            Analysis processed=processToStage(raw,stage,info,input,opt,cb);
            cb.onProgress(86,"توحيد مستوى البودكاست دون تشويه...");
            WavWriter.writeHeader(out,info.samples,info.sampleRate,1);
            normalizeStage(stage,out,info,processed,opt,cb);
            out.flush();cb.onProgress(100,"اكتملت النسخة البودكاستية");
        }finally{
            if(!raw.delete())raw.deleteOnExit();
            if(!stage.delete())stage.deleteOnExit();
        }
    }

    private static Analysis analyze(File pcm,int sr,int from,int to,String msg,Progress cb)throws Exception{
        Analysis a=new Analysis();long total=Math.max(1,pcm.length()/2);short[] b=new short[IO_SAMPLES];
        try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(pcm),1<<20)){
            long done=0;int n;while((n=readShorts(in,b))>0){double frameSum=0;for(int i=0;i<n;i++){double v=b[i]/32768.0;a.peak=Math.max(a.peak,Math.abs(v));frameSum+=v*v;}double rms=Math.sqrt(frameSum/Math.max(1,n));if(rms>0.0035){a.activeSum+=frameSum;a.activeCount+=n;}done+=n;if(done%(sr*8L)<n)cb.onProgress(from+(int)((to-from)*done/total),msg);}
        }
        a.activeRms=Math.sqrt(a.activeSum/Math.max(1,a.activeCount));if(a.activeRms<1e-6)a.activeRms=0.03;return a;
    }

    private static Analysis processToStage(File raw,File stage,DecodeInfo info,Analysis a,Options opt,Progress cb)throws Exception{
        int sr=info.sampleRate;
        Biquad hp=Biquad.highPass(sr,opt.preset==1?78:65,0.707);
        Biquad hum50=Biquad.notch(sr,50,12.0),hum100=Biquad.notch(sr,100,10.0);
        Biquad warmth=Biquad.lowShelf(sr,135,opt.preset==2?1.8:1.15);
        Biquad mud=Biquad.peak(sr,260,0.90,opt.preset==1?-2.0:-1.35);
        Biquad box=Biquad.peak(sr,520,1.05,-0.65);
        Biquad nasal=Biquad.peak(sr,980,1.20,-0.55);
        Biquad presence=Biquad.peak(sr,3000,0.95,opt.preset==3?2.25:1.55);
        Biquad air=Biquad.highShelf(sr,9000,opt.preset==3?1.2:0.60);
        Biquad essLP=Biquad.lowPass(sr,5200,0.707);
        double inputDb=20*Math.log10(a.activeRms+1e-9),desiredDb=opt.preset==1?-20.5:-19.4;
        double inputTrim=Math.pow(10,Math.max(-2.5,Math.min(6.0,desiredDb-inputDb))/20.0);
        double env=0,gain=1,essEnv=0,wet=0.97;
        double compAttack=Math.exp(-1.0/(0.022*sr)),compRelease=Math.exp(-1.0/(0.300*sr));
        double gainAttack=Math.exp(-1.0/(0.018*sr)),gainRelease=Math.exp(-1.0/(0.420*sr));
        double essAttack=Math.exp(-1.0/(0.005*sr)),essRelease=Math.exp(-1.0/(0.120*sr));
        double thresholdDb=opt.preset==1?-22.0:-20.5,ratio=opt.preset==1?2.8:2.45,makeup=Math.pow(10,(opt.preset==2?1.7:1.15)/20.0);
        Analysis result=new Analysis();Audx audx=null;
        try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(raw),1<<20);BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(stage),1<<20)){
            int frame=Math.max(80,sr/100);short[] input=new short[frame],cleaned=new short[frame];
            audx=opt.denoise?new Audx.Builder().inputRate(sr).resampleQuality(Audx.AUDX_RESAMPLER_QUALITY_MAX).build():null;
            long done=0,total=Math.max(1,info.samples);
            while(true){int valid=readShorts(in,input);if(valid<=0)break;if(valid<frame)for(int i=valid;i<frame;i++)input[i]=0;
                final float[] vad={1f};if(audx!=null)audx.process(input,cleaned,p->{vad[0]=p;return Unit.INSTANCE;});else System.arraycopy(input,0,cleaned,0,frame);
                double targetWet=vad[0]>0.55f?0.94:(vad[0]>0.18f?0.98:1.0);
                for(int i=0;i<valid;i++){
                    wet+=(targetWet-wet)*(targetWet>wet?0.004:0.0015);
                    double dry=input[i]/32768.0,den=cleaned[i]/32768.0;
                    float s=(float)((den*wet+dry*(1.0-wet))*inputTrim);
                    s=hp.run(s);s=hum50.run(s);s=hum100.run(s);s=warmth.run(s);s=mud.run(s);s=box.run(s);s=nasal.run(s);s=presence.run(s);s=air.run(s);
                    if(opt.deesser){float low=essLP.run(s);double hi=s-low,ah=Math.abs(hi),k=ah>essEnv?essAttack:essRelease;essEnv=k*essEnv+(1-k)*ah;double db=20*Math.log10(essEnv+1e-9),red=db>-23.5?Math.min(0.18,(db+23.5)/14.0*0.18):0;s=(float)(low+hi*(1-red));}
                    double abs=Math.abs(s),ck=abs>env?compAttack:compRelease;env=ck*env+(1-ck)*abs;double v=s;
                    if(opt.leveler){double levelDb=20*Math.log10(env+1e-9),grDb=levelDb>thresholdDb?-(levelDb-thresholdDb)*(1.0-1.0/ratio):0,target=Math.pow(10,grDb/20.0)*makeup,gk=target<gain?gainAttack:gainRelease;gain=gk*gain+(1-gk)*target;double comp=s*gain;v=0.38*s+0.62*comp;}
                    if(opt.master)v=Math.tanh(v*1.012)/Math.tanh(1.012);
                    v=Math.max(-0.98,Math.min(0.98,v));short q=(short)Math.round(v*32767.0);out.write(q&255);out.write((q>>>8)&255);
                    result.peak=Math.max(result.peak,Math.abs(v));if(Math.abs(v)>0.0035){result.activeSum+=v*v;result.activeCount++;}
                }
                done+=valid;if(done%(sr*5L)<valid)cb.onProgress(38+(int)(47.0*done/total),"تنقية وهندسة بودكاست: "+(int)(100.0*done/total)+"%");
            }
        }finally{if(audx!=null)audx.close();}
        result.activeRms=Math.sqrt(result.activeSum/Math.max(1,result.activeCount));if(result.activeRms<1e-6)result.activeRms=0.03;return result;
    }

    private static void normalizeStage(File stage,OutputStream out,DecodeInfo info,Analysis a,Options opt,Progress cb)throws Exception{
        double targetRms=opt.preset==1?Math.pow(10,-18.8/20.0):Math.pow(10,-18.0/20.0);
        double gain=targetRms/Math.max(1e-7,a.activeRms);gain=Math.max(0.70,Math.min(2.35,gain));
        double ceiling=Math.pow(10,-1.2/20.0);if(a.peak*gain>ceiling)gain=ceiling/Math.max(a.peak,1e-7);
        WavWriter.Pcm16Sink sink=new WavWriter.Pcm16Sink(out);short[] b=new short[IO_SAMPLES];long done=0,total=Math.max(1,info.samples);
        try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(stage),1<<20)){
            int n;while((n=readShorts(in,b))>0){for(int i=0;i<n;i++){double v=(b[i]/32768.0)*gain;if(opt.master){double av=Math.abs(v);if(av>ceiling){double over=av-ceiling;v=Math.copySign(ceiling+over/(1.0+over*70.0),v);}v=Math.max(-ceiling,Math.min(ceiling,v));}sink.write((short)Math.round(v*32767.0));}done+=n;if(done%(info.sampleRate*8L)<n)cb.onProgress(87+(int)(12.0*done/total),"تثبيت مستوى البودكاست: "+(int)(100.0*done/total)+"%");}
        }
        sink.flush();
    }

    private static DecodeInfo decodeToMonoFile(Context ctx,Uri uri,File raw,Progress cb)throws Exception{
        MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;
        try(BufferedOutputStream pcm=new BufferedOutputStream(new FileOutputStream(raw),1<<20)){
            ex.setDataSource(ctx,uri,null);int track=-1;MediaFormat fmt=null;
            for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String m=f.getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("audio/")){track=i;fmt=f;break;}}
            if(track<0||fmt==null)throw new Exception("لا يوجد مسار صوتي صالح");ex.selectTrack(track);String mime=fmt.getString(MediaFormat.KEY_MIME);codec=MediaCodec.createDecoderByType(mime);codec.configure(fmt,null,null,0);codec.start();
            int sr=fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)?fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE):48000,ch=fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;long dur=fmt.containsKey(MediaFormat.KEY_DURATION)?fmt.getLong(MediaFormat.KEY_DURATION):1;MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();boolean inDone=false,outDone=false;long samples=0;
            while(!outDone){if(!inDone){int idx=codec.dequeueInputBuffer(10000);if(idx>=0){ByteBuffer b=codec.getInputBuffer(idx);if(b==null)continue;b.clear();int n=ex.readSampleData(b,0);if(n<0){codec.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inDone=true;}else{codec.queueInputBuffer(idx,0,n,ex.getSampleTime(),0);ex.advance();}}}
                int o=codec.dequeueOutputBuffer(bi,10000);if(o==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat of=codec.getOutputFormat();if(of.containsKey(MediaFormat.KEY_SAMPLE_RATE))sr=of.getInteger(MediaFormat.KEY_SAMPLE_RATE);if(of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))ch=of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);}else if(o>=0){ByteBuffer b=codec.getOutputBuffer(o);if(b!=null&&bi.size>0){b.position(bi.offset);b.limit(bi.offset+bi.size);b.order(ByteOrder.LITTLE_ENDIAN);int shorts=bi.size/2,frames=shorts/Math.max(1,ch);for(int f=0;f<frames;f++){long sum=0;for(int c=0;c<ch;c++)sum+=b.getShort();short mono=(short)(sum/ch);pcm.write(mono&255);pcm.write((mono>>>8)&255);}samples+=frames;}if((bi.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outDone=true;codec.releaseOutputBuffer(o,false);cb.onProgress((int)Math.min(27,27.0*Math.max(0,bi.presentationTimeUs)/Math.max(1,dur)),"فك التسجيل الطويل إلى معالجة متدفقة...");}}
            DecodeInfo d=new DecodeInfo();d.sampleRate=sr;d.samples=samples;return d;
        }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}try{ex.release();}catch(Exception ignored){}}
    }

    private static int readShorts(InputStream in,short[] out)throws IOException{int i=0;while(i<out.length){int lo=in.read();if(lo<0)break;int hi=in.read();if(hi<0)break;out[i++]=(short)((hi<<8)|lo);}return i;}
    private static final class DecodeInfo{int sampleRate;long samples;}
    private static final class Analysis{double peak,activeSum,activeRms;long activeCount;}
}
