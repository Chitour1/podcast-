package com.acrps.podcastforge.audio;

import java.io.*;

final class FloatPcm {
    private FloatPcm() {}
    static final class Writer implements Closeable,Flushable {
        private final BufferedOutputStream out;private final byte[] buf=new byte[16384];private int used;
        Writer(File file)throws IOException{out=new BufferedOutputStream(new FileOutputStream(file),1<<20);}
        void write(float v)throws IOException{int bits=Float.floatToIntBits(v);if(used+4>buf.length)flushBuffer();buf[used++]=(byte)bits;buf[used++]=(byte)(bits>>>8);buf[used++]=(byte)(bits>>>16);buf[used++]=(byte)(bits>>>24);}
        private void flushBuffer()throws IOException{if(used>0){out.write(buf,0,used);used=0;}}
        @Override public void flush()throws IOException{flushBuffer();out.flush();}
        @Override public void close()throws IOException{flush();out.close();}
    }
    static final class Reader implements Closeable {
        private final BufferedInputStream in;Reader(File file)throws IOException{in=new BufferedInputStream(new FileInputStream(file),1<<20);}
        float read()throws IOException{int b0=in.read();if(b0<0)return Float.NaN;int b1=in.read(),b2=in.read(),b3=in.read();if((b1|b2|b3)<0)return Float.NaN;return Float.intBitsToFloat(b0|(b1<<8)|(b2<<16)|(b3<<24));}
        @Override public void close()throws IOException{in.close();}
    }
}
