package com.acrps.podcastforge.audio;

import java.io.*;
import java.util.Arrays;

final class MasteringEngine {
    private static final double EPS = 1e-12;
    private MasteringEngine() {}

    static void shape(File inputFloat, File outputFloat, long totalSamples, int sr,
                      PodcastProcessor.Analysis a, PodcastProcessor.Options opt,
                      PodcastProcessor.Progress progress) throws Exception {
        VoiceChain chain = new VoiceChain(sr, a, opt);
        try (FloatPcm.Reader in = new FloatPcm.Reader(inputFloat);
             FloatPcm.Writer out = new FloatPcm.Writer(outputFloat)) {
            long i = 0;
            while (i < totalSamples) {
                float x = in.read();
                if (Float.isNaN(x)) x = 0f;
                float y = chain.process(x, a.riderAt(i));
                if (!Float.isFinite(y)) y = x;
                out.write(clamp(y, -1.25f, 1.25f));
                i++;
                if (i % Math.max(1, sr * 8L) == 0) {
                    int p = 66 + (int) (18.0 * i / Math.max(1, totalSamples));
                    progress.onProgress(p, "تشكيل صوت المتحدث وتسوية الأداء: " + (int) (100.0 * i / Math.max(1, totalSamples)) + "%");
                }
            }
        }
    }

    static LoudnessResult measure(File inputFloat, long totalSamples, int sr,
                                  PodcastProcessor.Progress progress) throws Exception {
        Biquad shelf = Biquad.highShelf(sr, 1681.974, 4.0);
        Biquad highPass = Biquad.highPass(sr, 38.135, 0.5003);
        int block = Math.max(1, (int) Math.round(sr * 0.400));
        int hop = Math.max(1, (int) Math.round(sr * 0.100));
        double[] ring = new double[block];
        int ringPos = 0, filled = 0, sinceHop = 0;
        double sum = 0, samplePeak = 0;
        DoubleList energies = new DoubleList((int) Math.min(1_000_000, totalSamples / hop + 8));
        float p0 = 0, p1 = 0, p2 = 0;
        try (FloatPcm.Reader in = new FloatPcm.Reader(inputFloat)) {
            for (long i = 0; i < totalSamples; i++) {
                float x = in.read(); if (Float.isNaN(x)) x = 0;
                samplePeak = Math.max(samplePeak, Math.abs(x));
                if (i == 0) { p0 = p1 = p2 = x; }
                else { p0 = p1; p1 = p2; p2 = x; samplePeak = Math.max(samplePeak, cubicSegmentPeak(p0, p1, p2, p2)); }
                float y = highPass.run(shelf.run(x));
                double e = y * (double) y;
                if (filled < block) { ring[ringPos] = e; sum += e; filled++; }
                else { sum += e - ring[ringPos]; ring[ringPos] = e; }
                ringPos++; if (ringPos == block) ringPos = 0;
                if (filled == block && ++sinceHop >= hop) { energies.add(sum / block); sinceHop = 0; }
                if (i > 0 && i % Math.max(1, sr * 20L) == 0) {
                    progress.onProgress(84 + (int) (6.0 * i / Math.max(1, totalSamples)), "قياس LUFS والقمم الحقيقية...");
                }
            }
        }
        double absGateEnergy = Math.pow(10.0, (-70.0 + 0.691) / 10.0);
        double firstSum = 0; int firstCount = 0;
        for (int i = 0; i < energies.size; i++) if (energies.a[i] >= absGateEnergy) { firstSum += energies.a[i]; firstCount++; }
        double firstMean = firstCount > 0 ? firstSum / firstCount : Math.max(absGateEnergy, energies.mean());
        double relativeGateLufs = -0.691 + 10.0 * Math.log10(Math.max(EPS, firstMean)) - 10.0;
        double relativeGateEnergy = Math.pow(10.0, (relativeGateLufs + 0.691) / 10.0);
        double finalSum = 0; int finalCount = 0;
        double gate = Math.max(absGateEnergy, relativeGateEnergy);
        for (int i = 0; i < energies.size; i++) if (energies.a[i] >= gate) { finalSum += energies.a[i]; finalCount++; }
        double mean = finalCount > 0 ? finalSum / finalCount : firstMean;
        LoudnessResult result = new LoudnessResult();
        result.integratedLufs = -0.691 + 10.0 * Math.log10(Math.max(EPS, mean));
        result.estimatedTruePeak = Math.max(samplePeak, 1e-6);
        return result;
    }

    static void renderFinal(File inputFloat, OutputStream output, long totalSamples, int sr,
                            LoudnessResult loudness, PodcastProcessor.Progress progress) throws Exception {
        double desiredGainDb = clamp(-16.0 - loudness.integratedLufs, -8.0, 8.0);
        double ceiling = dbToLin(-1.0);
        double peakSafeGainDb = 20.0 * Math.log10(ceiling / Math.max(1e-9, loudness.estimatedTruePeak)) + 2.0;
        double appliedGain = dbToLin(Math.min(desiredGainDb, peakSafeGainDb));
        WavWriter.writeHeader(output, totalSamples, sr, 1);
        WavWriter.Pcm16Sink sink = new WavWriter.Pcm16Sink(output);
        CubicTruePeakLimiter limiter = new CubicTruePeakLimiter(sr, (float) ceiling, sink);
        try (FloatPcm.Reader in = new FloatPcm.Reader(inputFloat)) {
            float[] look = new float[3];
            for (int j = 0; j < 3; j++) look[j] = j < totalSamples ? readOrZero(in, true) * (float) appliedGain : 0f;
            float previous = look[0];
            for (long i = 0; i < totalSamples; i++) {
                float current = look[0], next = look[1], after = look[2];
                float candidate = cubicSegmentPeak(previous, current, next, after);
                limiter.accept(current, candidate);
                previous = current;
                look[0] = look[1]; look[1] = look[2];
                look[2] = i + 3 < totalSamples ? readOrZero(in, true) * (float) appliedGain : 0f;
                if (i > 0 && i % Math.max(1, sr * 10L) == 0) {
                    progress.onProgress(91 + (int) (8.0 * i / Math.max(1, totalSamples)), "تطبيع -16 LUFS وتحديد -1 dBTP...");
                }
            }
            limiter.finish();
            sink.flush();
        }
    }

    private static float readOrZero(FloatPcm.Reader in, boolean read) throws IOException {
        if (!read) return 0f;
        float v = in.read(); return Float.isNaN(v) ? 0f : v;
    }

    static final class LoudnessResult { double integratedLufs, estimatedTruePeak; }

    private static final class VoiceChain {
        private final Biquad hp, hum1, hum2, warmth, presence, air;
        private final DynamicEq plosive, mud, box, nasal, harsh, ess;
        private final Compressor leveller, peakCatcher;
        private double riderSmooth;
        private final double riderAttack, riderRelease;
        private final boolean useLeveler, useDeesser;

        VoiceChain(int sr, PodcastProcessor.Analysis a, PodcastProcessor.Options opt) {
            hp = Biquad.highPass(sr, opt.preset == 1 ? 78 : 65, 0.707);
            int hum = a.hum60 ? 60 : 50;
            hum1 = a.hum50 || a.hum60 ? Biquad.notch(sr, hum, 16) : null;
            hum2 = a.hum50 || a.hum60 ? Biquad.notch(sr, hum * 2.0, 12) : null;
            warmth = Biquad.lowShelf(sr, 155, clamp(a.warmthDb + (opt.preset == 2 ? 0.35 : 0.0), -0.25, 1.15));
            presence = Biquad.peak(sr, 2950, 0.85, clamp(a.presenceDb + (opt.preset == 3 ? 0.35 : 0.0), 0.45, 1.55));
            air = Biquad.highShelf(sr, 9000, a.snrDb > 17 ? clamp(a.airDb, 0, 0.35) : 0.0);

            plosive = new DynamicEq(sr, 45, 175, 125, 0.75, -5.0, -3.0, 8.0, 0.003, 0.100, true);
            mud = new DynamicEq(sr, 170, 370, 265, 0.90, -2.6, -5.0, 8.0, 0.030, 0.260, false);
            box = new DynamicEq(sr, 350, 820, 540, 1.00, -2.1, -4.0, 8.0, 0.035, 0.280, false);
            nasal = new DynamicEq(sr, 760, 1650, 1120, 1.15, -1.9, -3.5, 7.0, 0.025, 0.230, false);
            harsh = new DynamicEq(sr, 2300, 4800, 3350, 1.05, -2.2, -5.5, 7.5, 0.012, 0.170, false);
            ess = new DynamicEq(sr, 4300, Math.min(10500, sr * 0.45), 6900, 1.25, -3.4, -8.0, 8.0, 0.0025, 0.090, false);

            leveller = new Compressor(sr, -20.5, 1.65, 0.028, 0.240, 3.0, 0.65);
            peakCatcher = new Compressor(sr, -9.0, 2.8, 0.0035, 0.095, 2.2, 0.0);
            riderAttack = Math.exp(-1.0 / (0.180 * sr));
            riderRelease = Math.exp(-1.0 / (0.650 * sr));
            useLeveler = opt.leveler; useDeesser = opt.deesser;
        }

        float process(float x, double targetRiderDb) {
            float s = hp.run(x);
            if (hum1 != null) { s = hum1.run(s); s = hum2.run(s); }
            s = warmth.run(s); s = presence.run(s); s = air.run(s);
            if (useLeveler) {
                double target = dbToLin(clamp(targetRiderDb, -4.5, 4.5));
                double k = target < riderSmooth || riderSmooth == 0 ? riderAttack : riderRelease;
                if (riderSmooth == 0) riderSmooth = target; else riderSmooth = k * riderSmooth + (1 - k) * target;
                s *= (float) riderSmooth;
            }
            double full = Math.abs(s);
            s = plosive.process(s, full); s = mud.process(s, full); s = box.process(s, full);
            s = nasal.process(s, full); s = harsh.process(s, full);
            if (useDeesser) s = ess.process(s, full);
            if (useLeveler) { s = leveller.process(s); s = peakCatcher.process(s); }
            return s;
        }
    }

    private static final class DynamicEq {
        private final Biquad detectorHp, detectorLp, cut;
        private final double attack, release, triggerDb, rangeDb;
        private double bandEnv, fullEnv, amount;
        DynamicEq(int sr, double low, double high, double center, double q, double maxCutDb,
                  double triggerDb, double rangeDb, double attackSec, double releaseSec, boolean lowShelf) {
            detectorHp = Biquad.highPass(sr, Math.max(20, low), 0.707);
            detectorLp = Biquad.lowPass(sr, Math.min(sr * 0.47, high), 0.707);
            cut = lowShelf ? Biquad.lowShelf(sr, center, maxCutDb) : Biquad.peak(sr, center, q, maxCutDb);
            attack = Math.exp(-1.0 / (Math.max(0.0005, attackSec) * sr));
            release = Math.exp(-1.0 / (Math.max(0.005, releaseSec) * sr));
            this.triggerDb = triggerDb; this.rangeDb = rangeDb;
        }
        float process(float x, double fullAbs) {
            float band = detectorLp.run(detectorHp.run(x));
            double ab = Math.abs(band);
            double kb = ab > bandEnv ? attack : release;
            bandEnv = kb * bandEnv + (1 - kb) * ab;
            double kf = fullAbs > fullEnv ? attack : release;
            fullEnv = kf * fullEnv + (1 - kf) * fullAbs;
            double relativeDb = 20.0 * Math.log10((bandEnv + 1e-7) / (fullEnv + 1e-7));
            double wanted = clamp((relativeDb - triggerDb) / rangeDb, 0.0, 1.0);
            double ka = wanted > amount ? attack : release;
            amount = ka * amount + (1 - ka) * wanted;
            float filtered = cut.run(x);
            return (float) (x + amount * (filtered - x));
        }
    }

    private static final class Compressor {
        private final double thresholdDb, ratio, attack, release, maxReductionDb, parallelDry;
        private double env, gain = 1;
        Compressor(int sr, double thresholdDb, double ratio, double attackSec, double releaseSec,
                   double maxReductionDb, double parallelDry) {
            this.thresholdDb = thresholdDb; this.ratio = ratio;
            attack = Math.exp(-1.0 / (attackSec * sr)); release = Math.exp(-1.0 / (releaseSec * sr));
            this.maxReductionDb = maxReductionDb; this.parallelDry = parallelDry;
        }
        float process(float x) {
            double a = Math.abs(x), k = a > env ? attack : release;
            env = k * env + (1 - k) * a;
            double db = 20.0 * Math.log10(env + 1e-9);
            double reduction = db > thresholdDb ? -(db - thresholdDb) * (1.0 - 1.0 / ratio) : 0;
            reduction = Math.max(-maxReductionDb, reduction);
            double target = dbToLin(reduction);
            double kg = target < gain ? attack : release;
            gain = kg * gain + (1 - kg) * target;
            double wet = x * gain;
            return (float) (parallelDry * x + (1.0 - parallelDry) * wet);
        }
    }

    private static final class CubicTruePeakLimiter {
        private final int look, cap; private final float ceiling; private final WavWriter.Pcm16Sink sink;
        private final float[] sampleRing, dequeValue; private final long[] dequeIndex;
        private int dqHead, dqTail, dqCount; private long index; private double gain = 1.0;
        private final double release; private long rng = 0x4d595df4d0f33173L;
        CubicTruePeakLimiter(int sr, float ceiling, WavWriter.Pcm16Sink sink) {
            look = Math.max(64, (int) Math.round(sr * 0.006)); cap = look + 8;
            this.ceiling = ceiling; this.sink = sink;
            sampleRing = new float[cap]; dequeValue = new float[cap]; dequeIndex = new long[cap];
            release = Math.exp(-1.0 / (0.120 * sr));
        }
        void accept(float sample, float peakCandidate) throws IOException {
            sampleRing[(int) (index % cap)] = sample;
            while (dqCount > 0) { int back = (dqTail - 1 + cap) % cap; if (dequeValue[back] > peakCandidate) break; dqTail = back; dqCount--; }
            dequeValue[dqTail] = peakCandidate; dequeIndex[dqTail] = index; dqTail = (dqTail + 1) % cap; dqCount++;
            if (index >= look) {
                long outIndex = index - look;
                while (dqCount > 0 && dequeIndex[dqHead] < outIndex) { dqHead = (dqHead + 1) % cap; dqCount--; }
                double max = dqCount > 0 ? dequeValue[dqHead] : 0;
                double target = max > ceiling ? ceiling / (max + EPS) : 1.0;
                if (target < gain) gain = target; else gain = release * gain + (1 - release) * target;
                double y = sampleRing[(int) (outIndex % cap)] * gain + tpdf(); y = clamp(y, -ceiling, ceiling);
                sink.write((short) Math.round(y * 32767.0));
            }
            index++;
        }
        void finish() throws IOException { for (int i = 0; i < look; i++) accept(0f, 0f); }
        private double tpdf() {
            rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17; double a = ((rng >>> 11) & 0xffff) / 65535.0;
            rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17; double b = ((rng >>> 11) & 0xffff) / 65535.0;
            return (a - b) / 65536.0;
        }
    }

    private static float cubicSegmentPeak(float p0, float p1, float p2, float p3) {
        float peak = Math.max(Math.abs(p1), Math.abs(p2));
        for (int i = 1; i < 4; i++) {
            double t = i / 4.0;
            double a0 = -0.5 * p0 + 1.5 * p1 - 1.5 * p2 + 0.5 * p3;
            double a1 = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3;
            double a2 = -0.5 * p0 + 0.5 * p2; double a3 = p1;
            double y = ((a0 * t + a1) * t + a2) * t + a3;
            peak = Math.max(peak, (float) Math.abs(y));
        }
        return peak;
    }

    private static final class DoubleList {
        double[] a; int size;
        DoubleList(int cap) { a = new double[Math.max(16, cap)]; }
        void add(double v) { if (size == a.length) a = Arrays.copyOf(a, a.length + (a.length >> 1) + 1); a[size++] = v; }
        double mean() { if (size == 0) return 1e-9; double s = 0; for (int i = 0; i < size; i++) s += a[i]; return s / size; }
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double dbToLin(double db) { return Math.pow(10.0, db / 20.0); }
}
