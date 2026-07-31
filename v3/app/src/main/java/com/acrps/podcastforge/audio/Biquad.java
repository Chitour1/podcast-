package com.acrps.podcastforge.audio;

final class Biquad {
    private double b0,b1,b2,a1,a2,z1,z2;
    static Biquad highPass(double fs,double f,double q){Biquad x=new Biquad();double w=2*Math.PI*f/fs,c=Math.cos(w),s=Math.sin(w),a=s/(2*q),a0=1+a;x.b0=((1+c)/2)/a0;x.b1=(-(1+c))/a0;x.b2=((1+c)/2)/a0;x.a1=(-2*c)/a0;x.a2=(1-a)/a0;return x;}
    static Biquad lowPass(double fs,double f,double q){Biquad x=new Biquad();double w=2*Math.PI*f/fs,c=Math.cos(w),s=Math.sin(w),a=s/(2*q),a0=1+a;x.b0=((1-c)/2)/a0;x.b1=(1-c)/a0;x.b2=((1-c)/2)/a0;x.a1=(-2*c)/a0;x.a2=(1-a)/a0;return x;}
    static Biquad peak(double fs,double f,double q,double db){Biquad x=new Biquad();double A=Math.pow(10,db/40),w=2*Math.PI*f/fs,c=Math.cos(w),a=Math.sin(w)/(2*q),a0=1+a/A;x.b0=(1+a*A)/a0;x.b1=(-2*c)/a0;x.b2=(1-a*A)/a0;x.a1=(-2*c)/a0;x.a2=(1-a/A)/a0;return x;}
    static Biquad notch(double fs,double f,double q){Biquad x=new Biquad();double w=2*Math.PI*f/fs,c=Math.cos(w),a=Math.sin(w)/(2*q),a0=1+a;x.b0=1/a0;x.b1=(-2*c)/a0;x.b2=1/a0;x.a1=(-2*c)/a0;x.a2=(1-a)/a0;return x;}
    static Biquad lowShelf(double fs,double f,double db){Biquad x=new Biquad();double A=Math.pow(10,db/40),w=2*Math.PI*f/fs,c=Math.cos(w),s=Math.sin(w),r=2*Math.sqrt(A)*s;double a0=(A+1)+(A-1)*c+r;x.b0=A*((A+1)-(A-1)*c+r)/a0;x.b1=2*A*((A-1)-(A+1)*c)/a0;x.b2=A*((A+1)-(A-1)*c-r)/a0;x.a1=-2*((A-1)+(A+1)*c)/a0;x.a2=((A+1)+(A-1)*c-r)/a0;return x;}
    static Biquad highShelf(double fs,double f,double db){Biquad x=new Biquad();double A=Math.pow(10,db/40),w=2*Math.PI*f/fs,c=Math.cos(w),s=Math.sin(w),r=2*Math.sqrt(A)*s;double a0=(A+1)-(A-1)*c+r;x.b0=A*((A+1)+(A-1)*c+r)/a0;x.b1=-2*A*((A-1)+(A+1)*c)/a0;x.b2=A*((A+1)+(A-1)*c-r)/a0;x.a1=2*((A-1)-(A+1)*c)/a0;x.a2=((A+1)-(A-1)*c-r)/a0;return x;}
    float run(float in){double out=b0*in+z1;z1=b1*in-a1*out+z2;z2=b2*in-a2*out;return(float)out;}
}
