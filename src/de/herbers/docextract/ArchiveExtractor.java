package de.herbers.docextract;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Archive (ZIP/7z/TAR/TAR.GZ) durchsuchbar machen: Das Archiv bekommt EINEN
 * Index-Eintrag (wie jede Datei), dessen Volltext die Texte der enthaltenen
 * Dokumente umfasst. So ist ein Archiv ueber seinen Inhalt auffindbar, ohne
 * das Index-Schema (ein Eintrag je Datei) zu sprengen - eine Trefferzeile je
 * Archiv, kein Aufblaehen auf tausende Pseudo-Dateien.
 *
 * <p>Bewusst KEIN RAR: es gibt keinen freien, GPL-kompatiblen RAR-Entpacker
 * (der einzige vollstaendige ist Roshals unrar-Quelle unter der restriktiven
 * UnRar-Lizenz; junrar erbt diese und kann zudem nur RAR4). RAR bleibt beim
 * reinen Namens-Eintrag - wer RAR-Inhalte durchsuchen will, packt einmal als
 * ZIP oder 7z um.
 *
 * <p>Teuer (jeder Eintrag wird entpackt, in eine temporaere Datei geschrieben
 * und einzeln durch {@link FileExtractors} gejagt) - laeuft nur, wenn der
 * Aufrufer das mit {@code wantContent} ausdruecklich will (in Sucher hinter dem
 * separaten Schalter "Archive durchsuchen"). Verschachtelte Archive werden
 * NICHT rekursiv ausgepackt (Schutz vor Archiv-Bomben).
 */
public final class ArchiveExtractor {

    private ArchiveExtractor() {}

    /** Hoechstens so viele Eintraege je Archiv anfassen. */
    private static final int MAX_ENTRIES = 500;
    /** Einzelne Eintraege groesser als das werden uebersprungen (entpackt). */
    private static final long MAX_ENTRY_BYTES = 80L * 1024 * 1024;

    /** Endungen, deren Inhalt sich lohnt (dieselben, die FileExtractors kann -
     *  ohne Archive selbst (keine Rekursion), ohne reine Medien/Comics). */
    private static boolean indexableInner(String ext) {
        switch (ext) {
            case "txt": case "md": case "markdown": case "csv": case "log":
            case "json": case "xml": case "srt": case "ini": case "yaml": case "yml":
            case "html": case "htm":
            case "docx": case "xlsx": case "pptx":
            case "odt": case "ods": case "odp": case "odg": case "odf":
            case "ott": case "ots": case "otp":
            case "epub": case "fb2":
            case "doc": case "xls": case "ppt":
            case "mobi": case "azw": case "azw3": case "prc":
            case "pdf":
                return true;
            default:
                return false;
        }
    }

    public static FileExtractors.Result extract(File file, String ext, boolean wantContent) {
        FileExtractors.Result res = new FileExtractors.Result();
        if (!wantContent) return res; // Name wird ohnehin indiziert; Inhalt ist der teure Teil
        StringBuilder body = new StringBuilder();
        try {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if ("zip".equals(ext)) {
                readZip(file, body);
            } else if ("7z".equals(ext)) {
                readSevenZ(file, body);
            } else if ("tar".equals(ext)) {
                readTar(new BufferedInputStream(new FileInputStream(file)), body);
            } else if ("tgz".equals(ext) || name.endsWith(".tar.gz")) {
                readTar(new GzipCompressorInputStream(buffered(file)), body);
            } else if ("tbz".equals(ext) || "tbz2".equals(ext) || name.endsWith(".tar.bz2")) {
                readTar(new BZip2CompressorInputStream(buffered(file)), body);
            } else if ("txz".equals(ext) || name.endsWith(".tar.xz")) {
                readTar(new XZCompressorInputStream(buffered(file)), body);
            } else if ("gz".equals(ext) || "bz2".equals(ext) || "xz".equals(ext)) {
                // einzeln komprimierte Datei (z.B. bericht.pdf.gz) - auspacken und
                // die INNERE Datei (Endung ohne .gz/.bz2/.xz) durch FileExtractors.
                readSingleCompressed(file, ext, body);
            }
        } catch (Throwable t) {
            android.util.Log.w("EdgeTabSearch", "Archiv-Inhalt uebersprungen: " + file.getName(), t);
        }
        res.text = FileExtractors.cap(body.toString());
        return res;
    }

    private static BufferedInputStream buffered(File f) throws Exception {
        return new BufferedInputStream(new FileInputStream(f));
    }

    // ---- ZIP (wahlfreier Zugriff) ----
    private static void readZip(File file, StringBuilder body) throws Exception {
        try (ZipFile zf = new ZipFile(file)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            int count = 0;
            while (en.hasMoreElements()) {
                if (body.length() > FileExtractors.MAX_CHARS || count >= MAX_ENTRIES) break;
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                count++;
                if (e.getSize() > MAX_ENTRY_BYTES) continue;
                String inner = innerExt(e.getName());
                appendEntryName(body, e.getName());
                if (!indexableInner(inner)) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    indexOne(in, inner, body);
                }
            }
        }
    }

    // ---- 7z (wahlfreier Zugriff ueber getNextEntry) ----
    private static void readSevenZ(File file, StringBuilder body) throws Exception {
        try (SevenZFile sz = new SevenZFile(file)) {
            SevenZArchiveEntry e;
            int count = 0;
            while ((e = sz.getNextEntry()) != null) {
                if (body.length() > FileExtractors.MAX_CHARS || count >= MAX_ENTRIES) break;
                if (e.isDirectory()) continue;
                count++;
                if (e.getSize() > MAX_ENTRY_BYTES) continue;
                String inner = innerExt(e.getName());
                appendEntryName(body, e.getName());
                if (!indexableInner(inner)) continue;
                // 7z liefert den aktuellen Eintrag ueber sz.read(...) - ein
                // nicht-schliessender Wrapper reicht ihn an FileExtractors durch.
                indexOne(new SevenZEntryStream(sz), inner, body);
            }
        }
    }

    // ---- Einzeln komprimierte Datei (foo.pdf.gz / .bz2 / .xz) ----
    private static void readSingleCompressed(File file, String ext, StringBuilder body) throws Exception {
        String name = file.getName();
        // Endung der inneren Datei = alles vor dem .gz/.bz2/.xz.
        String stem = name.substring(0, name.length() - (ext.length() + 1)); // "bericht.pdf"
        String inner = innerExt(stem);
        appendEntryName(body, stem);
        if (!indexableInner(inner)) return;
        InputStream in;
        if ("gz".equals(ext)) in = new GzipCompressorInputStream(buffered(file));
        else if ("bz2".equals(ext)) in = new BZip2CompressorInputStream(buffered(file));
        else in = new XZCompressorInputStream(buffered(file));
        try { indexOne(in, inner, body); } finally { try { in.close(); } catch (Throwable ignored) {} }
    }

    // ---- TAR (auch als GZIP-Strom) ----
    private static void readTar(InputStream raw, StringBuilder body) throws Exception {
        try (TarArchiveInputStream tin = new TarArchiveInputStream(raw)) {
            TarArchiveEntry e;
            int count = 0;
            while ((e = tin.getNextTarEntry()) != null) {
                if (body.length() > FileExtractors.MAX_CHARS || count >= MAX_ENTRIES) break;
                if (e.isDirectory()) continue;
                count++;
                if (e.getSize() > MAX_ENTRY_BYTES) continue;
                String inner = innerExt(e.getName());
                appendEntryName(body, e.getName());
                if (!indexableInner(inner)) continue;
                indexOne(tin, inner, body); // tin darf hier NICHT geschlossen werden
            }
        }
    }

    /** Einen Archiv-Eintrag entpacken (in eine temporaere Datei, weil PDFBox/POI
     *  wahlfreien Datei-Zugriff brauchen) und durch FileExtractors jagen. */
    private static void indexOne(InputStream in, String inner, StringBuilder body) {
        File tmp = null;
        try {
            tmp = File.createTempFile("arc_", "." + inner);
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n; long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > MAX_ENTRY_BYTES) break;
                }
            }
            FileExtractors.Result r = FileExtractors.extract(tmp, inner, true);
            if (r.title != null) body.append(r.title).append(' ');
            if (r.author != null) body.append(r.author).append(' ');
            if (r.text != null && !r.text.isEmpty()) body.append(r.text).append('\n');
        } catch (Throwable t) {
            // ein kaputter Eintrag darf das ganze Archiv nicht reissen
        } finally {
            if (tmp != null) { try { tmp.delete(); } catch (Throwable ignored) {} }
        }
    }

    private static void appendEntryName(StringBuilder body, String entryName) {
        String base = entryName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (!base.isEmpty()) body.append(base).append('\n');
    }

    private static String innerExt(String entryName) {
        String n = entryName.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        return (dot > slash && dot >= 0) ? n.substring(dot + 1) : "";
    }

    /** Nicht-schliessender Wrapper um den aktuellen 7z-Eintrag: FileExtractors'
     *  Kopierschleife liest bis -1, darf aber die SevenZFile nicht schliessen. */
    private static final class SevenZEntryStream extends InputStream {
        private final SevenZFile sz;
        SevenZEntryStream(SevenZFile sz) { this.sz = sz; }
        @Override public int read() throws java.io.IOException { return sz.read(); }
        @Override public int read(byte[] b, int off, int len) throws java.io.IOException { return sz.read(b, off, len); }
        @Override public void close() { /* absichtlich nicht schliessen */ }
    }
}
