package com.acrps.podcastforge.audio;

import java.io.*;
import java.util.Arrays;

final class SpectralReconstructor {
    interface SpeechMap { double probabilityAt(long sampleIndex); }
    private SpectralReconstructor() {}

    static void process(File dryFile,File denoisedFile,File outputFloatFile,long totalSamples,int sampleRate,double strength,SpeechMap speechMap,PodcastProcessor.Progress progress)throws Exception{
        final int fftSize=sampleRate>=32000?2048:1024,hop=fftSize/2,half=fftSize/2;
        final float[] window=new float[fftSize];for(int i=0;i<fftSize;i++){double h=.5-.5*Math.cos(2*Math.PI*(i+.5)/fftSize);window[i]=(float)Math.sqrt(Math.max(0,h));}
        float[] dryFrame=new float[fftSize],cleanFrame=new float[fftSize],dryRe=new float[fftSize],dryIm=new float[fftSize],cleanRe=new float[fftSize],cleanIm=new float[fftSize];
        float[] dryMag=new float[half+1],cleanMag=new float[half+1],ratio=new float[half+1],temporal=new float[half+1],smoothed=new float[half+1],previousGain=new float[half+1],previousDryMag=new float[half+1],ola=new float[fftSize],norm=new float[fftSize];
        Arrays.fill(previousGain,1f);
        try(Pcm16Reader dry=new Pcm16Reader(dryFile);Pcm16Reader clean=new Pcm16Reader(denoisedFile);FloatPcm.Writer out=new FloatPcm.Writer(outputFloatFile)){
            long inputRead=0,outputWritten=0;int first=(int)Math.min(hop,totalSamples);inputRead+=readIntoTail(dry,clean,dryFrame,cleanFrame,hop,first);
            processFrame(dryFrame,cleanFrame,dryRe,dryIm,cleanRe,cleanIm,dryMag,cleanMag,ratio,temporal,smoothed,previousGain,previousDryMag,window,ola,norm,fftSize,half,strength,speechMap.probabilityAt(0),0);
            shiftOla(ola,norm,hop);double previousSpeech=speechMap.probabilityAt(0);
            while(outputWritten<totalSamples){
                System.arraycopy(dryFrame,hop,dryFrame,0,hop);System.arraycopy(cleanFrame,hop,cleanFrame,0,hop);Arrays.fill(dryFrame,hop,fftSize,0f);Arrays.fill(cleanFrame,hop,fftSize,0f);
                int want=(int)Math.min(hop,Math.max(0,totalSamples-inputRead));inputRead+=readIntoTail(dry,clean,dryFrame,cleanFrame,hop,want);
                long center=Math.min(totalSamples-1,outputWritten+hop/2L);double speech=speechMap.probabilityAt(Math.max(0,center));
                processFrame(dryFrame,cleanFrame,dryRe,dryIm,cleanRe,cleanIm,dryMag,cleanMag,ratio,temporal,smoothed,previousGain,previousDryMag,window,ola,norm,fftSize,half,strength,speech,previousSpeech);previousSpeech=speech;
                int emit=(int)Math.min(hop,totalSamples-outputWritten);for(int i=0;i<emit;i++){float value=norm[i]>1e-8f?ola[i]/norm[i]:dryFrame[i];if(!Float.isFinite(value))value=dryFrame[i];out.write(clamp(value,-1f,1f));}
                outputWritten+=emit;shiftOla(ola,norm,hop);if(outputWritten%Math.max(sampleRate*8L,1)<emit){int p=38+(int)(27.0*outputWritten/Math.max(1,totalSamples));progress.onProgress(p,"ترميم الطيف ومنع الصوت المعدني: "+(int)(100.0*outputWritten/Math.max(1,totalSamples))+"%");}
            }
        }
    }

    private static int readIntoTail(Pcm16Reader dry,Pcm16Reader clean,float[] dryFrame,float[] cleanFrame,int offset,int count)throws IOException{
        int read=0;for(;read<count;read++){short d=dry.read();if(dry.eof)break;short c=clean.read();dryFrame[offset+read]=d/32768f;cleanFrame[offset+read]=clean.eof?dryFrame[offset+read]:c/32768f;}return read;
    }

    private static void processFrame(float[] dryFrame,float[] cleanFrame,float[] dryRe,float[] dryIm,float[] cleanRe,float[] cleanIm,float[] dryMag,float[] cleanMag,float[] ratio,float[] temporal,float[] smoothed,float[] previousGain,float[] previousDryMag,float[] window,float[] ola,float[] norm,int fftSize,int half,double strength,double speechProbability,double previousSpeech){
        double dryEnergy=0,cleanEnergy=0;for(int i=0;i<fftSize;i++){float d=dryFrame[i],c=cleanFrame[i];dryEnergy+=d*d;cleanEnergy+=c*c;dryRe[i]=d*window[i];dryIm[i]=0;cleanRe[i]=c*window[i];cleanIm[i]=0;}
        double dryRms=Math.sqrt(dryEnergy/fftSize),cleanRms=Math.sqrt(cleanEnergy/fftSize);boolean unsafe=!Double.isFinite(cleanRms)||(dryRms>.008&&cleanRms<dryRms*.025)||cleanRms>Math.max(.12,dryRms*3.2);
        Radix2Fft.transform(dryRe,dryIm,false);Radix2Fft.transform(cleanRe,cleanIm,false);double fluxNum=0,fluxDen=1e-9;
        for(int k=0;k<=half;k++){float dm=(float)Math.hypot(dryRe[k],dryIm[k]),cm=(float)Math.hypot(cleanRe[k],cleanIm[k]);dryMag[k]=dm;cleanMag[k]=cm;fluxNum+=Math.max(0,dm-previousDryMag[k]);fluxDen+=dm;}
        double flux=fluxNum/fluxDen;boolean onset=flux>.105||speechProbability-previousSpeech>.28;
        for(int k=0;k<=half;k++){
            int lo=Math.max(0,k-2),hi=Math.min(half,k+2);double d=1e-9,c=0;for(int j=lo;j<=hi;j++){d+=dryMag[j];c+=cleanMag[j];}
            double r=unsafe?1:Math.pow(clamp(c/d,.015,1),.86),nf=k/(double)half,speechFloor=.53-.15*strength,silenceFloor=.25-.09*strength,floor=silenceFloor+(speechFloor-silenceFloor)*speechProbability;
            if(nf>.18&&speechProbability>.45)floor+=.055*speechProbability;double neighbor=1e-9;int count=0;for(int j=Math.max(0,k-3);j<=Math.min(half,k+3);j++)if(j!=k){neighbor+=dryMag[j];count++;}neighbor/=Math.max(1,count);
            if(speechProbability>.45&&dryMag[k]>neighbor*1.75)floor+=.075;if(onset)floor+=.12*Math.min(1,speechProbability+.25);ratio[k]=(float)Math.max(clamp(floor,.12,.78),r);
        }
        for(int k=0;k<=half;k++){double target=ratio[k],prev=previousGain[k],alpha=target<prev?(speechProbability>.5?.72:.56):(onset?.32:(speechProbability>.5?.72:.88));temporal[k]=(float)(alpha*prev+(1-alpha)*target);}
        for(int k=0;k<=half;k++){int lo=Math.max(0,k-2),hi=Math.min(half,k+2);double sum=0,weight=0;for(int j=lo;j<=hi;j++){double w=3-Math.abs(j-k);sum+=temporal[j]*w;weight+=w;}smoothed[k]=(float)clamp(sum/Math.max(1e-9,weight),.10,1);previousGain[k]=smoothed[k];previousDryMag[k]=dryMag[k];}
        for(int k=0;k<fftSize;k++){int mirror=k<=half?k:fftSize-k;float g=smoothed[mirror];dryRe[k]*=g;dryIm[k]*=g;}Radix2Fft.transform(dryRe,dryIm,true);
        for(int i=0;i<fftSize;i++){float w=window[i];ola[i]+=dryRe[i]*w;norm[i]+=w*w;}
    }

    private static void shiftOla(float[] ola,float[] norm,int hop){int remain=ola.length-hop;System.arraycopy(ola,hop,ola,0,remain);System.arraycopy(norm,hop,norm,0,remain);Arrays.fill(ola,remain,ola.length,0f);Arrays.fill(norm,remain,norm.length,0f);}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private static final class Pcm16Reader implements Closeable{private final BufferedInputStream in;boolean eof;Pcm16Reader(File file)throws IOException{in=new BufferedInputStream(new FileInputStream(file),1<<20);}short read()throws IOException{int lo=in.read();if(lo<0){eof=true;return 0;}int hi=in.read();if(hi<0){eof=true;return 0;}return(short)((hi<<8)|lo);}@Override public void close()throws IOException{in.close();}}
}
