package com.acrps.podcastforge.audio;

final class Radix2Fft {
    private Radix2Fft() {}
    static void transform(float[] real,float[] imag,boolean inverse){
        int n=real.length;if(n!=imag.length||Integer.bitCount(n)!=1)throw new IllegalArgumentException("FFT length must be a power of two");
        for(int i=1,j=0;i<n;i++){int bit=n>>>1;for(;(j&bit)!=0;bit>>>=1)j^=bit;j^=bit;if(i<j){float tr=real[i];real[i]=real[j];real[j]=tr;float ti=imag[i];imag[i]=imag[j];imag[j]=ti;}}
        for(int len=2;len<=n;len<<=1){double angle=(inverse?2.0:-2.0)*Math.PI/len,wlr=Math.cos(angle),wli=Math.sin(angle);int half=len>>>1;for(int base=0;base<n;base+=len){double wr=1,wi=0;for(int j=0;j<half;j++){int a=base+j,b=a+half;double br=real[b]*wr-imag[b]*wi,bi=real[b]*wi+imag[b]*wr,ar=real[a],ai=imag[a];real[a]=(float)(ar+br);imag[a]=(float)(ai+bi);real[b]=(float)(ar-br);imag[b]=(float)(ai-bi);double nw=wr*wlr-wi*wli;wi=wr*wli+wi*wlr;wr=nw;}}}
        if(inverse){float inv=1f/n;for(int i=0;i<n;i++){real[i]*=inv;imag[i]*=inv;}}
    }
}
