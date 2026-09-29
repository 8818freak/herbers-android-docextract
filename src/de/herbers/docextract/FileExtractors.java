package de.herbers.docextract;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Text UND Metadaten (Titel/Autor/Serie) aus Dateien ziehen - je Format die
 * schlankste Loesung statt schwerer Bibliotheken ueberall:
 *
 *  - TXT/MD: direkt lesen, keine Metadaten.
 *  - DOCX/XLSX/PPTX/EPUB: sind nur ZIP-Archive mit XML drin - mit Androids
 *    eingebautem XmlPullParser ausgelesen, kein Apache POI/eigene EPUB-Lib.
 *    Titel/Autor aus docProps/core.xml bzw. der EPUB-OPF (inkl. Calibres
 *    eigenen calibre:series/-series_index-Metafeldern).
 *  - FB2: reines XML, derselbe Parser - hat mit <sequence> sogar ein
 *    natives Serien-Feld.
 *  - MOBI/AZW3: siehe MobiExtractor (von Hand, PalmDOC ist simpel genug),
 *    Metadaten aus dem EXTH-Kopf.
 *  - DOC/XLS/PPT (alt, ohne "x"): siehe LegacyOfficeExtractor (Apache POI -
 *    das binaere OLE2-Format waere von Hand zu riskant/aufwendig).
 *  - PDF: siehe PdfExtractorHelper (PDFBox-Android), Metadaten aus
 *    PDDocumentInformation.
 *  - CBZ/CBR: siehe ComicExtractor (nur Metadaten, keine Bildinhalte).
 *
 * `wantContent` steuert, ob der TEURE Teil (voller Fliesstext) ueberhaupt
 * gemacht wird - Metadaten (Titel/Autor/Serie) sind ueberall billig und
 * werden IMMER gezogen, unabhaengig davon (siehe Mathias' Ordner-Schalter
 * "Inhalt durchsuchbar machen" in SearchIndexer/Settings).
 *
 * Groesse begrenzt (MAX_CHARS) - reicht fuer die Suche, verhindert aber dass
 * ein einzelnes Mammut-Dokument Indizierung/Speicher sprengt.
 */
final class FileExtractors {

    private FileExtractors() {}

    static final int MAX_CHARS = 400_000;

    /** Ergebnis einer Extraktion: Text (kann leer sein), Metadaten (koennen
     *  null sein - nicht jedes Format hat sie), + ob eine echte Kopiersperre
     *  erkannt wurde (dann bewusst kein Inhalt versucht). */
    static final class Result {
        String text = "";
        boolean drm = false;
        String title, author, series;
        float seriesIndex = 0f;
        Result() {}
        Result(String t) { text = t; }
    }

    static Result extract(File f, String extLower, boolean wantContent) {
        try {
            switch (extLower) {
                case "txt": case "md": case "markdown": case "csv": case "log":
                case "json": case "xml": case "srt": case "ini": case "yaml": case "yml":
                    return wantContent ? new Result(readPlain(f)) : new Result();
                case "docx":
                    return ooxml(f, "word/document.xml"::equals, wantContent);
                case "xlsx":
                    return ooxml(f, name -> name.equals("xl/sharedStrings.xml") || name.startsWith("xl/worksheets/"), wantContent);
                case "pptx":
                    return ooxml(f, name -> name.startsWith("ppt/slides/slide"), wantContent);
                case "epub":
                    return epub(f, wantContent);
                case "fb2":
                    return fb2(f, wantContent);
                case "doc": case "xls": case "ppt":
                    return LegacyOfficeExtractor.extract(f, extLower, wantContent);
                case "mobi": case "azw": case "azw3": case "prc":
                    return MobiExtractor.extract(f, wantContent);
                case "pdf":
                    return PdfExtractorHelper.extract(f, wantContent);
                case "cbz": case "cbr":
                    return ComicExtractor.extractMeta(f, extLower);
                default:
                    return new Result();
            }
        } catch (Throwable t) {
            // Jede Extraktion darf im Zweifel leer zurueckkommen statt den
            // ganzen Indizierlauf zu reissen - u.a. falls eine vendorte
            // Bibliothek auf dem Geraet eine fehlende Systemklasse braucht
            // (NoClassDefFoundError ist ein Error, kein Exception - darum
            // Throwable, nicht nur Exception).
            android.util.Log.w("EdgeTabSearch", "Extraktion fehlgeschlagen: " + f.getName(), t);
            return new Result();
        }
    }

    private static String readPlain(File f) throws Exception {
        byte[] buf = new byte[Math.min((int) f.length(), MAX_CHARS * 2)];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = in.read(buf);
            if (n < 0) return "";
            return cap(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
    }

    interface EntryFilter { boolean matches(String name); }

    /** DOCX/XLSX/PPTX: docProps/core.xml fuer Titel/Autor (immer, billig),
     *  der eigentliche Inhalt nur wenn gewuenscht. */
    private static Result ooxml(File f, EntryFilter contentFilter, boolean wantContent) throws Exception {
        Result r = new Result();
        try (ZipFile zf = new ZipFile(f)) {
            ZipEntry core = zf.getEntry("docProps/core.xml");
            if (core != null) {
                try (InputStream in = zf.getInputStream(core)) {
                    Set<String> want = new HashSet<>(); want.add("title"); want.add("creator");
                    Map<String, String> m = tagTexts(in, want);
                    r.title = emptyToNull(m.get("title"));
                    r.author = emptyToNull(m.get("creator"));
                }
            }
            if (wantContent) {
                StringBuilder sb = new StringBuilder();
                java.util.Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory() || !contentFilter.matches(e.getName())) continue;
                    try (InputStream in = zf.getInputStream(e)) {
                        sb.append(xmlText(in, NONE_SKIP)).append('\n');
                    }
                    if (sb.length() > MAX_CHARS) break;
                }
                r.text = cap(sb.toString());
            }
        }
        return r;
    }

    /** EPUB: META-INF/container.xml zeigt auf die OPF-Datei, deren
     *  <dc:title>/<dc:creator> sowie (bei Calibre-Buechern, bei Mathias'
     *  Bibliothek sehr wahrscheinlich) <meta name="calibre:series" .../>
     *  liefern Titel/Autor/Serie - immer, billig. Der Fliesstext (alle
     *  .xhtml-Dateien) nur wenn gewuenscht. */
    /** META-INF/container.xml zeigt auf die OPF-Datei; manche (kaputten)
     *  EPUBs haben das nicht, dann die erste .opf-Datei im Archiv nehmen.
     *  Gemeinsam genutzt von der Metadaten-Extraktion und der
     *  Cover-Suche (siehe epubCoverBytes). */
    private static String resolveOpfPath(ZipFile zf) throws Exception {
        ZipEntry container = zf.getEntry("META-INF/container.xml");
        if (container != null) {
            try (InputStream in = zf.getInputStream(container)) {
                String p = firstAttr(in, "rootfile", "full-path");
                if (p != null) return p;
            }
        }
        java.util.Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".opf")) return e.getName();
        }
        return null;
    }

    private static Result epub(File f, boolean wantContent) throws Exception {
        Result r = new Result();
        try (ZipFile zf = new ZipFile(f)) {
            String opfPath = resolveOpfPath(zf);
            if (opfPath != null) {
                ZipEntry opf = zf.getEntry(opfPath);
                if (opf != null) {
                    try (InputStream in = zf.getInputStream(opf)) {
                        parseOpfMeta(in, r);
                    }
                }
            }
            if (wantContent) {
                StringBuilder sb = new StringBuilder();
                java.util.Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String n = e.getName().toLowerCase(java.util.Locale.ROOT);
                    if (e.isDirectory() || !(n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))) continue;
                    try (InputStream in = zf.getInputStream(e)) {
                        sb.append(xmlText(in, NONE_SKIP)).append('\n');
                    }
                    if (sb.length() > MAX_CHARS) break;
                }
                r.text = cap(sb.toString());
            }
        }
        return r;
    }

    /** Cover-Bild eines EPUBs, fuer die Miniaturvorschau (Thumbnails.java) -
     *  entweder ueber das EPUB3-eigene <item properties="cover-image"/> oder
     *  das aeltere, verbreitetere <meta name="cover" content="ID"/> (zeigt auf
     *  die Item-ID im Manifest, deren href dann relativ zum OPF-Ordner
     *  aufgeloest wird). Manche Buecher haben keins von beidem - dann null,
     *  Thumbnails.java faellt auf das generische Dateisymbol zurueck. */
    static byte[] epubCoverBytes(File f) {
        try (ZipFile zf = new ZipFile(f)) {
            String opfPath = resolveOpfPath(zf);
            if (opfPath == null) return null;
            ZipEntry opf = zf.getEntry(opfPath);
            if (opf == null) return null;
            String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            String coverHref;
            try (InputStream in = zf.getInputStream(opf)) {
                coverHref = findEpubCoverHref(in);
            }
            if (coverHref == null) return null;
            ZipEntry img = zf.getEntry(opfDir + coverHref);
            if (img == null) return null;
            try (InputStream in = zf.getInputStream(img)) { return readAll(in); }
        } catch (Throwable t) { return null; }
    }

    private static String findEpubCoverHref(InputStream in) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new InputStreamReader(in, StandardCharsets.UTF_8));
        Map<String, String> idToHref = new HashMap<>();
        String coverMetaId = null, coverPropId = null;
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String local = localName(p.getName());
                if (local.equals("item")) {
                    String id = attr(p, "id");
                    String href = attr(p, "href");
                    String props = attr(p, "properties");
                    if (id != null && href != null) idToHref.put(id, href);
                    if (props != null && props.contains("cover-image") && id != null) coverPropId = id;
                } else if (local.equals("meta")) {
                    String name = attr(p, "name");
                    String content = attr(p, "content");
                    if ("cover".equals(name) && content != null) coverMetaId = content;
                }
            }
            try { event = p.next(); } catch (Exception e) { break; }
        }
        String id = coverMetaId != null ? coverMetaId : coverPropId;
        return id != null ? idToHref.get(id) : null;
    }

    static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static void parseOpfMeta(InputStream in, Result r) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new InputStreamReader(in, StandardCharsets.UTF_8));
        int event = p.getEventType();
        String currentTag = null;
        StringBuilder buf = null;
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String local = localName(p.getName());
                if (local.equals("title") || local.equals("creator")) {
                    currentTag = local; buf = new StringBuilder();
                } else if (local.equals("meta")) {
                    String name = attr(p, "name");
                    String content = attr(p, "content");
                    if ("calibre:series".equals(name) && content != null && !content.trim().isEmpty()) {
                        r.series = content.trim();
                    } else if ("calibre:series_index".equals(name) && content != null) {
                        try { r.seriesIndex = Float.parseFloat(content.trim()); } catch (Exception ignored) {}
                    }
                }
            } else if (event == XmlPullParser.TEXT && currentTag != null) {
                buf.append(p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                String local = localName(p.getName());
                if (local.equals(currentTag)) {
                    String val = buf.toString().trim();
                    if (!val.isEmpty()) {
                        if (currentTag.equals("title") && r.title == null) r.title = val;
                        if (currentTag.equals("creator") && r.author == null) r.author = val;
                    }
                    currentTag = null; buf = null;
                }
            }
            try { event = p.next(); } catch (Exception e) { break; }
        }
    }

    /** FB2: <title-info><book-title>/<author>/<sequence name= number=>
     *  liefern Titel/Autor/Serie nativ - immer, billig (steht am Anfang der
     *  Datei). Fliesstext (ganzes Dokument, ohne <binary>) nur wenn
     *  gewuenscht. */
    private static Result fb2(File f, boolean wantContent) throws Exception {
        Result r = new Result();
        try (InputStream in = new FileInputStream(f)) {
            parseFb2Meta(in, r);
        }
        if (wantContent) {
            try (InputStream in = new FileInputStream(f)) {
                r.text = xmlText(in, FB2_SKIP);
            }
        }
        return r;
    }

    private static void parseFb2Meta(InputStream in, Result r) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new InputStreamReader(in, StandardCharsets.UTF_8));
        int event = p.getEventType();
        boolean inTitleInfo = false, inAuthor = false, done = false;
        String currentTag = null;
        StringBuilder buf = null;
        String firstName = null, lastName = null;
        while (event != XmlPullParser.END_DOCUMENT && !done) {
            if (event == XmlPullParser.START_TAG) {
                String local = localName(p.getName());
                if (local.equals("title-info")) {
                    inTitleInfo = true;
                } else if (inTitleInfo && local.equals("author")) {
                    inAuthor = true;
                } else if (inTitleInfo && local.equals("sequence")) {
                    String name = attr(p, "name");
                    if (name != null && !name.trim().isEmpty()) r.series = name.trim();
                    String num = attr(p, "number");
                    if (num != null) try { r.seriesIndex = Float.parseFloat(num.trim()); } catch (Exception ignored) {}
                } else if (inTitleInfo && (local.equals("book-title")
                        || (inAuthor && (local.equals("first-name") || local.equals("last-name"))))) {
                    currentTag = local; buf = new StringBuilder();
                }
            } else if (event == XmlPullParser.TEXT && currentTag != null) {
                buf.append(p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                String local = localName(p.getName());
                if (local.equals(currentTag)) {
                    String val = buf.toString().trim();
                    if (local.equals("book-title")) r.title = val;
                    else if (local.equals("first-name")) firstName = val;
                    else if (local.equals("last-name")) lastName = val;
                    currentTag = null; buf = null;
                } else if (local.equals("author")) {
                    inAuthor = false;
                } else if (local.equals("title-info")) {
                    inTitleInfo = false; done = true; // alles Weitere ist Buchinhalt, nicht Metadaten
                }
            }
            try { event = p.next(); } catch (Exception e) { break; }
        }
        if ((firstName != null && !firstName.isEmpty()) || (lastName != null && !lastName.isEmpty())) {
            r.author = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        }
    }

    /** Erstes Vorkommen eines Tags + eine seiner Attribute finden (z.B.
     *  EPUBs <rootfile full-path="..."/>). */
    private static String firstAttr(InputStream in, String tagLocal, String attrName) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new InputStreamReader(in, StandardCharsets.UTF_8));
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && localName(p.getName()).equals(tagLocal)) {
                String v = attr(p, attrName);
                if (v != null) return v;
            }
            event = p.next();
        }
        return null;
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String an = p.getAttributeName(i);
            String local = an.contains(":") ? an.substring(an.indexOf(':') + 1) : an;
            if (local.equalsIgnoreCase(name)) return p.getAttributeValue(i);
        }
        return null;
    }

    /** Textinhalt bestimmter, gesuchter Elemente einsammeln (erstes
     *  Vorkommen je Name) - fuer gezielte Metadaten-Felder wie
     *  docProps/core.xmls dc:title/dc:creator, anders als xmlText() unten,
     *  das PAUSCHAL allen Text einsammelt (fuer den Fliesstext-Index). */
    private static Map<String, String> tagTexts(InputStream in, Set<String> wanted) throws Exception {
        Map<String, String> out = new HashMap<>();
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new InputStreamReader(in, StandardCharsets.UTF_8));
        int event = p.getEventType();
        String currentTag = null;
        StringBuilder buf = null;
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String local = localName(p.getName());
                if (wanted.contains(local) && !out.containsKey(local)) {
                    currentTag = local; buf = new StringBuilder();
                }
            } else if (event == XmlPullParser.TEXT && currentTag != null) {
                buf.append(p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                String local = localName(p.getName());
                if (local.equals(currentTag)) {
                    out.put(currentTag, buf.toString().trim());
                    currentTag = null; buf = null;
                }
            }
            try { event = p.next(); } catch (Exception e) { break; }
        }
        return out;
    }

    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }

    private static final Set<String> NONE_SKIP = new HashSet<>();
    private static final Set<String> FB2_SKIP = new HashSet<>();
    static { FB2_SKIP.add("binary"); }

    /** Alle Text-Knoten eines XML-Dokuments einsammeln, bestimmte Element-
     *  namen (klein geschrieben, ohne Namensraum-Praefix) dabei ueberspringen
     *  - z.B. FB2s eingebettete Bilder (<binary>, Base64) waeren sonst
     *  nutzloser "Text" im Index. */
    private static String xmlText(InputStream in, Set<String> skipTags) throws Exception {
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(reader);
        StringBuilder sb = new StringBuilder();
        int depthSkip = 0;
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String local = localName(p.getName());
                if (depthSkip > 0 || skipTags.contains(local)) depthSkip++;
            } else if (event == XmlPullParser.END_TAG) {
                if (depthSkip > 0) depthSkip--;
            } else if (event == XmlPullParser.TEXT && depthSkip == 0) {
                String t = p.getText();
                if (t != null) {
                    sb.append(t);
                    if (sb.length() < 4 || t.length() > 0) sb.append(' ');
                }
            }
            if (sb.length() > MAX_CHARS) break;
            try { event = p.next(); } catch (Exception e) { break; } // kaputtes XML: bisher Gesammeltes behalten
        }
        return cap(sb.toString());
    }

    private static String localName(String name) {
        int i = name.indexOf(':');
        return (i >= 0 ? name.substring(i + 1) : name).toLowerCase();
    }

    static String cap(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) : s;
    }
}
