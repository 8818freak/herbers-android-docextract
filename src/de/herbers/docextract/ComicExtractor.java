package de.herbers.docextract;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

/**
 * Comics (CBZ/CBR) sind Bildseiten, keine durchsuchbaren Texte - echte
 * Volltextsuche ergibt hier keinen Sinn (kein OCR). Stattdessen nur
 * Metadaten indizieren:
 *  - CBZ (ZIP): falls eine ComicInfo.xml drinsteckt (bei Scans/Scanlations
 *    ueblich), deren Felder strukturiert uebernehmen - <Series>/<Title>/
 *    <Writer>/<Number> werden zu Serie/Titel/Autor/Serien-Nummer (dieselben
 *    Spalten wie bei Buechern), plus die Zusammenfassung als durchsuchbarer
 *    Fliesstext.
 *  - CBR (RAR): dafuer einen RAR-Leser nur fuers Metadaten-Auslesen zu
 *    vendoren stand in keinem Verhaeltnis zum Nutzen - bleibt bei reiner
 *    Namenssuche (Dateiname landet ueber SearchStore.upsert ohnehin immer
 *    im Index, unabhaengig vom Inhalt).
 */
final class ComicExtractor {

    private ComicExtractor() {}

    static FileExtractors.Result extractMeta(File f, String ext) {
        FileExtractors.Result res = new FileExtractors.Result();
        if (!"cbz".equals(ext)) return res;
        try (ZipFile zf = new ZipFile(f)) {
            java.util.zip.ZipEntry e = zf.getEntry("ComicInfo.xml");
            if (e == null) return res;
            try (InputStream in = zf.getInputStream(e)) {
                parseComicInfo(in, res);
            }
        } catch (Throwable ignored) {}
        return res;
    }

    private static boolean isImageName(String n) {
        n = n.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".webp");
    }

    /** Alle Bildseiten eines CBZ, in Leserichtung sortiert (Dateiname) - fuer
     *  die Titelbild-Miniatur (erste Seite) UND den Seiten-Vorschaubetrachter
     *  (Quick-Look-Blättern, siehe PreviewActivity). */
    static java.util.List<String> cbzPageNames(File f) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (ZipFile zf = new ZipFile(f)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (!e.isDirectory() && isImageName(e.getName())) out.add(e.getName());
            }
            java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        } catch (Throwable ignored) {}
        return out;
    }

    static byte[] cbzEntryBytes(File f, String entryName) {
        try (ZipFile zf = new ZipFile(f)) {
            java.util.zip.ZipEntry e = zf.getEntry(entryName);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) { return FileExtractors.readAll(in); }
        } catch (Throwable t) { return null; }
    }

    /** Titelbild = erste Bildseite (Leserichtung), fuers Miniaturbild in der
     *  Trefferliste. */
    static byte[] cbzCoverBytes(File f) {
        java.util.List<String> pages = cbzPageNames(f);
        return pages.isEmpty() ? null : cbzEntryBytes(f, pages.get(0));
    }

    private static void parseComicInfo(InputStream in, FileExtractors.Result res) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder();
        int event = p.getEventType();
        String currentTag = null;
        StringBuilder buf = null;
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                currentTag = p.getName();
                buf = new StringBuilder();
            } else if (event == XmlPullParser.TEXT) {
                String t = p.getText();
                if (t != null) {
                    if (buf != null) buf.append(t);
                    if (!t.trim().isEmpty()) body.append(t).append(' ');
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (buf != null) {
                    String val = buf.toString().trim();
                    if (!val.isEmpty()) {
                        switch (currentTag) {
                            case "Series": res.series = val; break;
                            case "Title": res.title = val; break;
                            case "Writer": res.author = val; break;
                            case "Number":
                                try { res.seriesIndex = Float.parseFloat(val); } catch (Exception ignored) {}
                                break;
                        }
                    }
                }
                currentTag = null; buf = null;
            }
            event = p.next();
        }
        res.text = FileExtractors.cap(body.toString());
    }
}
