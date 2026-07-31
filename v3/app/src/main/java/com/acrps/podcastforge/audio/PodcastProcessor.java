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
        File raw=File.createTempFile("pf_raw_",".pcm",ctx.getCacheDir());
        try{
            DecodeInfo info=decodeToMonoFile(ctx,uri,raw,cb);
            if(info.samples<=0)throw new Exception("لم يُفك أي صوت صالح من الملف");
            Analysis a=analyze(raw,info.sampleRate,cb);
            cb.onProgress(43,"بدء التنقية المحافظة على طبيعة الصوت...");
            WavWriter.writeHeader(out,info.samples,info.sampleRate,1);
            processStream(raw,out,info,a,opt,cb);
            out.flush();cb.onProgress(100,"اكتملت النسخة البودكاستية");
        }finally{if(!raw.delete())raw.deleteOnExit();}
    }

    private static Analysis analyze(File raw,int sr,Progress cb)throws Exception{
        Analysis a=new Analysis();long total=Math.max(1,raw.length()/2);short[] b=new short[IO_SAMPLES];
        try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(raw),1<<20)){
            long done=0;int n;while((n=readShorts(in,b))>0){double frameSum=0;for(int i=0;i<n;i++){double v=b[i]/32768.0;a.peak=Math.max(a.peak,Math.abs(v));frameSum+=v*v;}double rms=Math.sqrt(frameSum/Math.max(1,n));if(rms>0.0045){a.activeSum+=frameSum;a.activeCount+=n;}done+=n;if(done%(sr*8L)<n)cb.onProgress(30+(int)(12.0*done/total),"تحليل الكلام والمستوى الديناميكي...");}
        }
        a.activeRms=Math.sqrt(a.activeSum/Math.max(1,a.activeCount));if(a.activeRms<1e-6)a.activeRms=0.03;return a;
    }

    private static void processStream(File raw,OutputStream out,DecodeInfo info,Analysis a,Options opt,Progress cb)throws Exception{
        int sr=info.sampleRate;
        Biquad hp=Biquad.highPass(sr,opt.preset==1?82:68,0.707),hum50=Biquad.notch(sr,50,10.0),hum100=Biquad.notch(sr,100,9.0),warmth=Biquad.lowShelf(sr,145,opt.preset==2?1.7:1.0),mud=Biquad.peak(sr,245,0.85,opt.preset==1?-2.2:-1.35),nasal=Biquad.peak(sr,920,1.15,-0.65),presence=Biquad.peak(sr,2850,0.92,opt.preset==3?2.1:1.35),air=Biquad.highShelf(sr,7800,opt.preset==3?0.9:0.35),essLP=Biquad.lowPass(sr,4800,0.707);
        double inputDb=20*Math.log10(a.activeRms+1e-9),desiredInputDb=opt.preset==1?-20.2:-19.2,trimDb=Math.max(-3.0,Math.min(7.0,desiredInputDb-inputDb)),inputTrim=Math.pow(10,trimDb/20.0);
        double env=0,gain=1,essEnv=0,wetSmooth=0.52;
        double compAttack=Math.exp(-1.0/(0.018*sr)),compRelease=Math.exp(-1.0/(0.260*sr)),gainAttack=Math.exp(-1.0/(0.014*sr)),gainRelease=Math.exp(-1.0/(0.350*sr)),essAttack=Math.exp(-1.0/(0.004*sr)),essRelease=Math.exp(-1.0/(0.095*sr));
        double thresholdDb=opt.preset==1?-20.5:-19.0,ratio=opt.preset==1?2.7:2.35,makeup=Math.pow(10,(opt.preset==2?1.4:0.9)/20.0),ceiling=0.8709636;
        Audx audx=null;
        try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(raw),1<<20)){
            int frame=Math.max(80,sr/100);short[] input=new short[frame],cleaned=new short[frame];audx=opt.denoise?new Audx.Builder().inputRate(sr).resampleQuality(Audx.AUDX_RESAMPLER_QUALITY_MAX).build():null;long done=0,total=Math.max(1,info.samples);byte[] outBytes=new byte[frame*2];
            while(true){int valid=readShorts(in,input);if(valid<=0)break;if(valid<frame)for(int i=valid;i<frame;i++)input[i]=0;final float[] vad={1f};if(audx!=null)audx.process(input,cleaned,p->{vad[0]=p;return Unit.INSTANCE;});else System.arraycopy(input,0,cleaned,0,frame);
                double desiredWet=vad[0]>0.55f?0.42:(vad[0]>0.20f?0.55:0.72);
                for(int i=0;i<valid;i++){
                    wetSmooth+=(desiredWet-wetSmooth)*(desiredWet>wetSmooth?0.0018:0.0045);
                    double dry=input[i]/32768.0,den=cleaned[i]/32768.0;float s=(float)((dry*(1.0-wetSmooth)+den*wetSmooth)*inputTrim);
                    s=hp.run(s);s=hum50.run(s);s=hum100.run(s);s=warmth.run(s);s=mud.run(s);s=nasal.run(s);s=presence.run(s);s=air.run(s);
                    if(opt.deesser){float low=essLP.run(s);double hi=s-low,ah=Math.abs(hi),ek=ah>essEnv?essAttack:essRelease;essEnv=ek*essEnv+(1-ek)*ah;double essDb=20*Math.log10(essEnv+1e-9),reduction=essDb>-25.5?Math.min(0.27,(essDb+25.5)/12.0*0.27):0;s=(float)(low+hi*(1.0-reduction));}
                    double abs=Math.abs(s),ck=abs>env?compAttack:compRelease;env=ck*env+(1-ck)*abs;double compressed=s;
                    if(opt.leveler){double levelDb=20*Math.log10(env+1e-9),grDb=levelDb>thresholdDb?-(levelDb-thresholdDb)*(1.0-1.0/ratio):0,target=Math.pow(10,grDb/20.0)*makeup,gk=target<gain?gainAttack:gainRelease;gain=gk*gain+(1-gk)*target;compressed=s*gain;compressed=0.28*s+0.72*compressed;}
                    double v=compressed;if(opt.master){v=Math.tanh(v*1.035)/Math.tanh(1.035);double av=Math.abs(v);if(av>ceiling){double over=av-ceiling;v=Math.copySign(ceiling+over/(1.0+over*38.0),v);}v=Math.max(-ceiling,Math.min(ceiling,v));}
                    short q=(short)Math.round(Math.max(-1.0,Math.min(0.999969,v))*32767.0);outBytes[i*2]=(byte)(q&255);outBytes[i*2+1]=(byte)((q>>>8)&255);
                }
                out.write(outBytes,0,valid*2);done+=valid;if(done%(sr*5L)<valid)cb.onProgress(44+(int)(54.0*done/total),"تنقية شفافة وهندسة بودكاست: "+(int)(100.0*done/total)+"%");
            }
        }finally{if(audx!=null)audx.close();}
    }

    private static DecodeInfo decodeToMonoFile(Context ctx,Uri uri,File raw,Progress cb)throws Exception{
        MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;
        try(BufferedOutputStream pcm=new BufferedOutputStream(new FileOutputStream(raw),1<<20)){
            ex.setDataSource(ctx,uri,null);int track=-1;MediaFormat fmt=null;for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String m=f.getString(MediaFormat.KEY_MIME);if(m!=null&&m.startsWith("audio/")){track=i;fmt=f;break;}}if(track<0||fmt==null)throw new Exception("لا يوجد مسار صوتي صالح");ex.selectTrack(track);String mime=fmt.getString(MediaFormat.KEY_MIME);codec=MediaCodec.createDecoderByType(mime);codec.configure(fmt,null,null,0);codec.start();int sr=fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)?fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE):48000,ch=fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;long dur=fmt.containsKey(MediaFormat.KEY_DURATION)?fmt.getLong(MediaFormat.KEY_DURATION):1;MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();boolean inDone=false,outDone=false;long samples=0;
            while(!outDone){if(!inDone){int idx=codec.dequeueInputBuffer(10000);if(idx>=0){ByteBuffer b=codec.getInputBuffer(idx);if(b==null)continue;b.clear();int n=ex.readSampleData(b,0);if(n<0){codec.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inDone=true;}else{codec.queueInputBuffer(idx,0,n,ex.getSampleTime(),0);ex.advance();}}}int o=codec.dequeueOutputBuffer(bi,10000);if(o==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat of=codec.getOutputFormat();if(of.containsKey(MediaFormat.KEY_SAMPLE_RATE))sr=of.getInteger(MediaFormat.KEY_SAMPLE_RATE);if(of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))ch=of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);}else if(o>=0){ByteBuffer b=codec.getOutputBuffer(o);if(b!=null&&bi.size>0){b.position(bi.offset);b.limit(bi.offset+bi.size);b.order(ByteOrder.LITTLE_ENDIAN);int shorts=bi.size/2,frames=shorts/Math.max(1,ch);for(int f=0;f<frames;f++){long sum=0;for(int c=0;c<ch;c++)sum+=b.getShort();short mono=(short)(sum/ch);pcm.write(mono&255);pcm.write((mono>>>8)&255);}samples+=frames;}if((bi.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outDone=true;codec.releaseOutputBuffer(o,false);cb.onProgress((int)Math.min(29,29.0*Math.max(0,bi.presentationTimeUs)/Math.max(1,dur)),"فك التسجيل الطويل إلى مسار معالجة متدفق...");}}
            DecodeInfo d=new DecodeInfo();d.sampleRate=sr;d.samples=samples;return d;
        }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}try{ex.release();}catch(Exception ignored){}}
    }

    private static int readShorts(InputStream in,short[] out)throws IOException{int i=0;while(i<out.length){int lo=in.read();if(lo<0)break;int hi=in.read();if(hi<0)break;out[i++]=(short)((hi<<8)|lo);}return i;}
    private static final class DecodeInfo{int sampleRate;long samples;}
    private static final class Analysis{double peak,activeSum,activeRms;long activeCount;}
}
