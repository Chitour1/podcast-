package com.acrps.podcastforge.audio;
import java.io.*;
final class WavWriter {
    static void writeHeader(OutputStream out,long samples,int sampleRate,int channels)throws IOException{
        long data=samples*2L; if(data>0xffffffffL)throw new IOException("ملف WAV يتجاوز حد 4 غيغابايت");
        writeAscii(out,"RIFF");le32(out,(int)(36+data));writeAscii(out,"WAVEfmt ");le32(out,16);le16(out,1);le16(out,channels);le32(out,sampleRate);le32(out,sampleRate*channels*2);le16(out,channels*2);le16(out,16);writeAscii(out,"data");le32(out,(int)data);
    }
    private static void writeAscii(OutputStream o,String s)throws IOException{o.write(s.getBytes("US-ASCII"));}
    private static void le16(OutputStream o,int v)throws IOException{o.write(v&255);o.write((v>>>8)&255);}
    private static void le32(OutputStream o,int v)throws IOException{o.write(v&255);o.write((v>>>8)&255);o.write((v>>>16)&255);o.write((v>>>24)&255);}
}
