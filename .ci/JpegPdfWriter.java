package com.webcapture.ultra;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class JpegPdfWriter implements Closeable {
    private final CountingOutputStream out;
    private final long[] offsets;
    private final int pageCount;
    private int pageIndex = 0;
    private boolean finished = false;

    JpegPdfWriter(OutputStream destination, int pageCount) throws IOException {
        if (pageCount < 1) throw new IllegalArgumentException("pageCount");
        this.out = new CountingOutputStream(destination);
        this.pageCount = pageCount;
        this.offsets = new long[3 + pageCount * 3];
        write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
        object(1, "<< /Type /Catalog /Pages 2 0 R >>\n");
        StringBuilder kids = new StringBuilder("<< /Type /Pages /Kids [");
        for (int i = 0; i < pageCount; i++) kids.append(3 + i * 3).append(" 0 R ");
        kids.append("] /Count ").append(pageCount).append(" >>\n");
        object(2, kids.toString());
    }

    void addPage(byte[] jpeg, int imageWidth, int imageHeight) throws IOException {
        if (finished || pageIndex >= pageCount) throw new IOException("PDF page overflow");
        int pageObj = 3 + pageIndex * 3;
        int imageObj = pageObj + 1;
        int contentObj = pageObj + 2;
        float pageW = 595f;
        float pageH = Math.max(1f, pageW * imageHeight / (float)Math.max(1, imageWidth));
        String ph = String.format(Locale.US, "%.3f", pageH);
        object(pageObj, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 " + ph + "] /Resources << /XObject << /Im0 " + imageObj + " 0 R >> >> /Contents " + contentObj + " 0 R >>\n");
        beginObject(imageObj);
        write("<< /Type /XObject /Subtype /Image /Width " + imageWidth + " /Height " + imageHeight + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + jpeg.length + " >>\nstream\n");
        out.write(jpeg);
        write("\nendstream\nendobj\n");
        byte[] content = ("q\n595 0 0 " + ph + " 0 0 cm\n/Im0 Do\nQ\n").getBytes(StandardCharsets.US_ASCII);
        beginObject(contentObj);
        write("<< /Length " + content.length + " >>\nstream\n");
        out.write(content);
        write("endstream\nendobj\n");
        pageIndex++;
    }

    void finish() throws IOException {
        if (finished) return;
        if (pageIndex != pageCount) throw new IOException("PDF incomplete: " + pageIndex + "/" + pageCount);
        int objectCount = 2 + pageCount * 3;
        long xref = out.count;
        write("xref\n0 " + (objectCount + 1) + "\n");
        write("0000000000 65535 f \n");
        for (int i = 1; i <= objectCount; i++) write(String.format(Locale.US, "%010d 00000 n \n", offsets[i]));
        write("trailer\n<< /Size " + (objectCount + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        out.flush();
        finished = true;
    }

    private void object(int number, String body) throws IOException { beginObject(number); write(body); write("endobj\n"); }
    private void beginObject(int number) throws IOException { offsets[number] = out.count; write(number + " 0 obj\n"); }
    private void write(String s) throws IOException { out.write(s.getBytes(StandardCharsets.ISO_8859_1)); }
    @Override public void close() throws IOException { if (!finished && pageIndex == pageCount) finish(); }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate; long count = 0;
        CountingOutputStream(OutputStream d) { delegate = d; }
        @Override public void write(int b) throws IOException { delegate.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { delegate.write(b, off, len); count += len; }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
