package com.acrps.podcastforge.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class WavWriter {
    static void writeHeader(OutputStream out,long samples,int sampleRate,int channels)throws IOException{
        long data=samples*channels*2L, riff=36L+data;
        if(data>0xffffffffL)throw new IOException("ملف WAV يتجاوز حد 4 غيغابايت");
        writeAscii(out,"RIFF");le32(out,riff);writeAscii(out,"WAVEfmt ");le32(out,16);le16(out,1);le16(out,channels);
        le32(out,sampleRate);le32(out,(long)sampleRate*channels*2);le16(out,channels*2);le16(out,16);writeAscii(out,"data");le32(out,data);
    }
    static final class Pcm16Sink {
        private final OutputStream out;private final byte[] buffer=new byte[16384];private int used=0;
        Pcm16Sink(OutputStream out){this.out=out;}
        void write(short s)throws IOException{buffer[used++]=(byte)(s&255);buffer[used++]=(byte)((s>>>8)&255);if(used==buffer.length)flush();}
        void flush()throws IOException{if(used>0){out.write(buffer,0,used);used=0;}out.flush();}
    }
    private static void writeAscii(OutputStream o,String s)throws IOException{o.write(s.getBytes(StandardCharsets.US_ASCII));}
    private static void le16(OutputStream o,int v)throws IOException{o.write(v&255);o.write((v>>>8)&255);}
    private static void le32(OutputStream o,long v)throws IOException{o.write((int)(v&255));o.write((int)((v>>>8)&255));o.write((int)((v>>>16)&255));o.write((int)((v>>>24)&255));}
}
