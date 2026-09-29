package de.herbers.docextract;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Von Hand geschriebener MOBI/AZW3-Leser - keine Bibliothek dafuer vendort,
 * weil das PalmDOC-Format (die Grundlage von MOBI) simpel genug ist, um es
 * direkt zu implementieren: eine PalmDB-Datei ist ein Datensatz-Verzeichnis
 * plus (meist) LZ77-artig komprimierte Text-Datensaetze.
 *
 * Deckt ab: unkomprimierte und PalmDOC(LZ77)-komprimierte Buecher, MOBI6 wie
 * AZW3/KF8 (die intern meist denselben Datensatz-Aufbau fuer den Text
 * mitfuehren - bestenfalls, nicht perfekt: KF8-spezifisches Markup wird nur
 * grob als Text mitgenommen, nicht als echtes HTML geparst).
 *
 * NICHT abgedeckt: HUFF/CDIC-komprimierte Buecher (Kompressionstyp 17480,
 * ein Huffman-Verfahren mit eigenem Woerterbuch - deutlich aufwendiger,
 * kommt bei manchen konvertierten/gekauften Buechern vor) - dafuer nur
 * Name/Metadaten, kein Inhalt. Und: kopiergeschuetzte (DRM) Buecher werden
 * erkannt und bewusst NICHT angefasst.
 *
 * Titel/Autor kommen aus dem EXTH-Metadatenblock direkt hinter dem MOBI-
 * Kopf (Typ 503 = aktualisierter Titel, Typ 100 = Autor, kann mehrfach
 * vorkommen - der erste zaehlt). Kein natives Serien-Feld in MOBI selbst;
 * manche Konverter schreiben sowas in eigene, uneinheitliche EXTH-Typen -
 * bewusst NICHT geraten, bleibt leer statt falscher Serien-Zuordnung.
 * Fallback fuers Titel-Feld: die 32-Byte-Datenbankname aus dem PalmDB-Kopf
 * (Byte 0-31 der Datei), falls EXTH keinen liefert. Byte-Layout nach dem
 * oeffentlich dokumentierten MOBI-Format (MobileRead-Wiki); auf einem
 * echten Geraet noch nicht gegengeprueft - im Zweifel bleibt ein Feld
 * einfach leer statt eine falsche Zeile zu erzeugen.
 */
public final class MobiExtractor {

    private MobiExtractor() {}

    static FileExtractors.Result extract(java.io.File file, boolean wantContent) throws Exception {
        FileExtractors.Result res = new FileExtractors.Result();
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long len = f.length();
            if (len < 78) return res;

            // PalmDB-Datenbankname (Byte 0-31) - Rueckfalltitel, falls EXTH
            // keinen liefert.
            byte[] dbName = new byte[32];
            f.seek(0);
            f.readFully(dbName);
            String fallbackTitle = cleanCString(dbName);

            f.seek(76);
            int numRecords = f.readUnsignedShort();
            if (numRecords <= 0) return res;

            int[] offsets = new int[numRecords + 1];
            f.seek(78);
            for (int i = 0; i < numRecords; i++) {
                offsets[i] = f.readInt();
                f.skipBytes(4); // Attribute/UniqueID, hier egal
            }
            offsets[numRecords] = (int) len;

            // Record 0 = PalmDOC-Header (16 Byte) gefolgt vom MOBI-Header.
            long record0 = offsets[0];
            f.seek(record0);
            int compression = f.readUnsignedShort();
            f.skipBytes(2);
            f.skipBytes(4); // textLength (unkomprimierte Gesamtlaenge) - hier nicht gebraucht
            int textRecordCount = f.readUnsignedShort();
            f.skipBytes(2); // recordSize (idR 4096)
            int encryptionType = f.readUnsignedShort();

            if (encryptionType != 0) {
                res.drm = true;
                return res; // kopiergeschuetzt - Inhalt bewusst nicht versucht
            }

            readMobiMeta(f, record0, len, res, fallbackTitle);

            if (!wantContent) return res;
            if (compression != 1 && compression != 2) {
                // HUFF/CDIC (17480) oder unbekannt - nicht implementiert.
                return res;
            }

            textRecordCount = Math.min(textRecordCount, numRecords - 1);
            // Zeichensatz aus dem MOBI-Kopf (Feld 0x1C, relativ zu record0+16).
            int textEncoding = 0;
            if (record0 + 32 <= len) { f.seek(record0 + 28); textEncoding = f.readInt(); } // MOBI-Kopf 0x0C
            java.nio.charset.Charset cs = mobiCharset(textEncoding);
            // Bytes sammeln und ERST danach dekodieren (ein UTF-8-Zeichen kann
            // ueber eine Record-Grenze reichen). Frueher fest Latin-1 -> Mojibake.
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            for (int rec = 1; rec <= textRecordCount; rec++) {
                int start = offsets[rec];
                int end = offsets[rec + 1];
                if (start < 0 || end <= start || end > len) continue;
                byte[] raw = new byte[end - start];
                f.seek(start);
                f.readFully(raw);
                byte[] piece = (compression == 2) ? decompressPalmDoc(raw) : raw;
                body.write(piece, 0, piece.length);
                if (body.size() > FileExtractors.MAX_CHARS * 4) break;
            }
            // KF8/MOBI-Text enthaelt oft eingebettetes HTML-Markup -
            // grob strippen, damit im Index lesbarer Fliesstext steht statt
            // Tag-Suppe.
            res.text = FileExtractors.cap(stripTags(new String(body.toByteArray(), cs)));
        }
        return res;
    }

    /** MOBI-Kopf (ab record0+16) + darin/dahinter der EXTH-Block (falls das
     *  Flag-Bit gesetzt ist) fuer Titel/Autor. Alles defensiv mit
     *  Grenzenpruefung - ein falsch gelesenes Feld darf hoechstens leer
     *  bleiben, nie eine Exception nach aussen werfen. */
    private static void readMobiMeta(RandomAccessFile f, long record0, long fileLen,
                                      FileExtractors.Result res, String fallbackTitle) {
        try {
            long mobiStart = record0 + 16;
            if (mobiStart + 4 > fileLen) { res.title = fallbackTitle; return; }
            f.seek(mobiStart);
            byte[] ident = new byte[4];
            f.readFully(ident);
            if (!"MOBI".equals(new String(ident, StandardCharsets.US_ASCII))) {
                res.title = fallbackTitle;
                return;
            }
            f.seek(mobiStart + 4);
            long headerLength = f.readInt() & 0xFFFFFFFFL;

            // full_name_offset/-length (relativ zum Record-0-Anfang) - der
            // "richtige" Buchtitel, unabhaengig vom EXTH-Block.
            String mobiTitle = null;
            if (mobiStart + 92 <= fileLen) {
                f.seek(mobiStart + 84);
                long nameOffset = f.readInt() & 0xFFFFFFFFL;
                int nameLen = f.readInt();
                if (nameLen > 0 && nameLen < 1000 && record0 + nameOffset + nameLen <= fileLen) {
                    byte[] nameBuf = new byte[nameLen];
                    f.seek(record0 + nameOffset);
                    f.readFully(nameBuf);
                    mobiTitle = new String(nameBuf, StandardCharsets.UTF_8).trim();
                }
            }

            // EXTH-Flag (Bit 6 = 0x40) im MOBI-Header, ueblicherweise bei
            // mobiStart+128 - EXTH-Block folgt direkt nach dem MOBI-Header
            // (Laenge = headerLength, ab mobiStart).
            String exthAuthor = null, exthTitle = null;
            if (mobiStart + 132 <= fileLen) {
                f.seek(mobiStart + 128);
                long exthFlags = f.readInt() & 0xFFFFFFFFL;
                if ((exthFlags & 0x40) != 0 && headerLength > 0 && headerLength < 10_000) {
                    long exthStart = mobiStart + headerLength;
                    if (exthStart + 12 <= fileLen) {
                        f.seek(exthStart);
                        byte[] exthIdent = new byte[4];
                        f.readFully(exthIdent);
                        if ("EXTH".equals(new String(exthIdent, StandardCharsets.US_ASCII))) {
                            f.skipBytes(4); // EXTH-Kopflaenge, hier nicht gebraucht
                            long recCount = f.readInt() & 0xFFFFFFFFL;
                            long pos = exthStart + 12;
                            for (long i = 0; i < recCount && i < 500 && pos + 8 <= fileLen; i++) {
                                f.seek(pos);
                                long type = f.readInt() & 0xFFFFFFFFL;
                                long recLen = f.readInt() & 0xFFFFFFFFL;
                                if (recLen < 8 || pos + recLen > fileLen) break;
                                int dataLen = (int) (recLen - 8);
                                if ((type == 100 && exthAuthor == null) || (type == 503 && exthTitle == null)) {
                                    byte[] data = new byte[dataLen];
                                    f.seek(pos + 8);
                                    f.readFully(data);
                                    String val = new String(data, StandardCharsets.UTF_8).trim();
                                    if (type == 100) exthAuthor = val; else exthTitle = val;
                                }
                                pos += recLen;
                            }
                        }
                    }
                }
            }

            res.author = nullIfEmpty(exthAuthor);
            String title = nullIfEmpty(exthTitle);
            if (title == null) title = nullIfEmpty(mobiTitle);
            if (title == null) title = nullIfEmpty(fallbackTitle);
            res.title = title;
        } catch (Throwable t) {
            res.title = fallbackTitle; // besser ein grober Titel als gar keiner
        }
    }

    private static String nullIfEmpty(String s) { return (s == null || s.isEmpty()) ? null : s; }

    private static String cleanCString(byte[] raw) {
        int end = 0;
        while (end < raw.length && raw[end] != 0) end++;
        return new String(raw, 0, end, StandardCharsets.UTF_8).trim();
    }

    /** PalmDOC-Dekompression (LZ77-artig, RFC-lose aber ein seit Jahrzehnten
     *  stabiler, oeffentlich dokumentierter Byte-Code):
     *  0        -> Literal 0x00
     *  1..8     -> die naechsten c Bytes wortwoertlich uebernehmen
     *  9..0x7F  -> das Byte selbst ist ein druckbares ASCII-Zeichen
     *  0x80..0xBF -> 2-Byte Rueckverweis (Distanz+Laenge) auf bereits
     *                dekomprimierten Text
     *  0xC0..0xFF -> Leerzeichen + (Byte XOR 0x80) als naechstes Zeichen */
    /** PalmDOC/LZ77 auf BYTES dekomprimieren (NICHT dekodieren) - der Zeichensatz
     *  wird erst nach dem Zusammensetzen aller Records angewandt, sonst kann ein
     *  mehrbyte-Zeichen (UTF-8) an einer Record-/Kopiergrenze zerbrechen. */
    private static byte[] decompressPalmDoc(byte[] data) {
        byte[] out = new byte[Math.max(16, data.length * 3)];
        int n = 0, pos = 0;
        while (pos < data.length) {
            int c = data[pos++] & 0xFF;
            if (c >= 1 && c <= 8) {
                for (int i = 0; i < c && pos < data.length; i++) { out = grow(out, n + 1); out[n++] = data[pos++]; }
            } else if (c <= 0x7F) {
                out = grow(out, n + 1); out[n++] = (byte) c;
            } else if (c >= 0xC0) {
                out = grow(out, n + 2); out[n++] = (byte) ' '; out[n++] = (byte) (c ^ 0x80);
            } else if (pos < data.length) { // 0x80..0xBF: Rueckverweis (LZ77)
                int c2 = data[pos++] & 0xFF;
                int distance = (((c & 0x3F) << 8) | c2) >> 3;
                int length = (c2 & 0x07) + 3;
                int start = n - distance;
                if (start < 0) continue; // beschaedigter Datensatz - ueberspringen
                for (int i = 0; i < length; i++) {
                    int idx = start + i;
                    if (idx < 0 || idx >= n) break; // liest den LIVE-Puffer -> ueberlappende Kopien ok
                    out = grow(out, n + 1); out[n++] = out[idx];
                }
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static byte[] grow(byte[] a, int need) {
        if (need <= a.length) return a;
        int cap = a.length * 2; if (cap < need) cap = need;
        return java.util.Arrays.copyOf(a, cap);
    }

    /** Text-Zeichensatz eines MOBI aus dem Header-Feld (0x1C): 65001 = UTF-8,
     *  sonst Windows-1252 (MOBI-Standard; ISO-8859-1 als Notrueckfall). */
    private static java.nio.charset.Charset mobiCharset(int textEncoding) {
        if (textEncoding == 65001) return StandardCharsets.UTF_8;
        try { return java.nio.charset.Charset.forName("windows-1252"); }
        catch (Throwable t) { return StandardCharsets.ISO_8859_1; }
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]{0,500}>", " ");
    }

    /** Titelbild fuer die Miniatur - EXTH-Typ 201 (Cover-Offset) bzw. 202
     *  (Thumbnail-Offset) sind Record-INDIZES relativ zu "First Image Index"
     *  (MOBI-Kopf, Byte 108 - dieselbe Quelle wie die schon in readMobiMeta()
     *  genutzten Felder, siehe Klassenkommentar zur Unsicherheit der Layout-
     *  Angaben). Ohne EXTH-Eintrag wird der erste Bild-Record selbst versucht
     *  (bei vielen einfacheren Buechern tatsaechlich das Titelbild). Ein
     *  Magic-Byte-Check am Ende faengt ab, falls die Offset-Rechnung daneben
     *  liegt - lieber kein Bild als ein kaputtes. */
    static byte[] coverBytes(File file) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long len = f.length();
            if (len < 78) return null;
            f.seek(76);
            int numRecords = f.readUnsignedShort();
            if (numRecords <= 0) return null;
            int[] offsets = new int[numRecords + 1];
            f.seek(78);
            for (int i = 0; i < numRecords; i++) { offsets[i] = f.readInt(); f.skipBytes(4); }
            offsets[numRecords] = (int) len;

            long record0 = offsets[0];
            long mobiStart = record0 + 16;
            if (mobiStart + 4 > len) return null;
            f.seek(mobiStart);
            byte[] ident = new byte[4];
            f.readFully(ident);
            if (!"MOBI".equals(new String(ident, StandardCharsets.US_ASCII))) return null;
            f.seek(mobiStart + 4);
            long headerLength = f.readInt() & 0xFFFFFFFFL;

            // "First Image Index" (MOBI-Kopf Byte 108) ist in vielen Dateien 0
            // bzw. unzuverlaessig (an echten Dateien bestaetigt: Feld=0, obwohl
            // Bilder vorhanden). Robuster: den ERSTEN Record finden, der wirklich
            // ein Bild ist (Magic-Bytes) - die EXTH-Offsets (201/202) beziehen
            // sich darauf.
            long firstImageIndex = -1;
            byte[] head = new byte[8];
            for (int i = 0; i < numRecords; i++) {
                int s0 = offsets[i], e0 = offsets[i + 1];
                if (s0 < 0 || e0 - s0 < 4 || e0 > len) continue;
                int n0 = Math.min(8, e0 - s0);
                f.seek(s0);
                f.readFully(head, 0, n0);
                if (looksLikeImage(head)) { firstImageIndex = i; break; }
            }
            if (firstImageIndex < 0) return null; // kein Bild-Record vorhanden

            Long coverOff = null, thumbOff = null;
            if (mobiStart + 132 <= len) {
                f.seek(mobiStart + 128);
                long exthFlags = f.readInt() & 0xFFFFFFFFL;
                if ((exthFlags & 0x40) != 0 && headerLength > 0 && headerLength < 10_000) {
                    long exthStart = mobiStart + headerLength;
                    if (exthStart + 12 <= len) {
                        f.seek(exthStart);
                        byte[] exthIdent = new byte[4];
                        f.readFully(exthIdent);
                        if ("EXTH".equals(new String(exthIdent, StandardCharsets.US_ASCII))) {
                            f.skipBytes(4);
                            long recCount = f.readInt() & 0xFFFFFFFFL;
                            long pos = exthStart + 12;
                            for (long i = 0; i < recCount && i < 500 && pos + 8 <= len; i++) {
                                f.seek(pos);
                                long type = f.readInt() & 0xFFFFFFFFL;
                                long recLen = f.readInt() & 0xFFFFFFFFL;
                                if (recLen < 8 || pos + recLen > len) break;
                                int dataLen = (int) (recLen - 8);
                                if ((type == 201 || type == 202) && dataLen == 4) {
                                    f.seek(pos + 8);
                                    long val = f.readInt() & 0xFFFFFFFFL;
                                    if (type == 201 && coverOff == null) coverOff = val;
                                    if (type == 202 && thumbOff == null) thumbOff = val;
                                }
                                pos += recLen;
                            }
                        }
                    }
                }
            }

            Long chosen = coverOff != null ? coverOff : thumbOff;
            long imgRecord = chosen != null ? firstImageIndex + chosen : firstImageIndex;
            if (imgRecord < 0 || imgRecord >= numRecords) imgRecord = firstImageIndex;
            byte[] raw = readRecord(f, offsets, (int) imgRecord, len);
            if (looksLikeImage(raw)) return raw;
            // Offset-Rechnung daneben? Dann den ersten Bild-Record selbst nehmen.
            if (imgRecord != firstImageIndex) {
                byte[] raw2 = readRecord(f, offsets, (int) firstImageIndex, len);
                if (looksLikeImage(raw2)) return raw2;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Bytes eines PalmDB-Records (idx) anhand der Offset-Tabelle lesen. */
    private static byte[] readRecord(RandomAccessFile f, int[] offsets, int idx, long len) {
        try {
            if (idx < 0 || idx + 1 >= offsets.length) return null;
            int start = offsets[idx], end = offsets[idx + 1];
            if (start < 0 || end <= start || end > len) return null;
            byte[] raw = new byte[end - start];
            f.seek(start);
            f.readFully(raw);
            return raw;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean looksLikeImage(byte[] b) {
        if (b == null || b.length < 4) return false;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return true; // JPEG
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true; // PNG
        return b[0] == 'G' && b[1] == 'I' && b[2] == 'F'; // GIF
    }

    /** Ergebnis fuer die Quick-Look-Vorschau (PreviewActivity) - anders als
     *  extract() werden die Text-Records HIER NICHT von Tags befreit, weil
     *  klassisches MOBI6 darin meist schon fertiges, direkt anzeigbares HTML
     *  mitfuehrt (WebView kann das nativ darstellen). KF8/AZW3-Dateien
     *  speichern ihren Text stattdessen in getrennten skeleton/flow-
     *  Abschnitten, die sich NICHT einfach als zusammenhaengendes HTML lesen
     *  lassen (das vollstaendig zu rekonstruieren waere ein eigenes Projekt)
     *  - werden per EXTH-Typ 121 (KF8-Boundary-Record-Index, nur in
     *  Kombi-Dateien vorhanden) erkannt und bekommen bewusst nur die reine
     *  Textvariante (kf8=true signalisiert das dem Aufrufer). */
    public static final class PreviewResult {
        public boolean drm, kf8, unsupportedCompression;
        public String html; // bei kf8=true: roher Text ohne Tag-Anspruch, kein echtes HTML
    }

    public static PreviewResult loadPreview(File file) {
        PreviewResult pr = new PreviewResult();
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long len = f.length();
            if (len < 78) return pr;
            f.seek(76);
            int numRecords = f.readUnsignedShort();
            if (numRecords <= 0) return pr;
            int[] offsets = new int[numRecords + 1];
            f.seek(78);
            for (int i = 0; i < numRecords; i++) { offsets[i] = f.readInt(); f.skipBytes(4); }
            offsets[numRecords] = (int) len;

            long record0 = offsets[0];
            f.seek(record0);
            int compression = f.readUnsignedShort();
            f.skipBytes(2);
            f.skipBytes(4);
            int textRecordCount = f.readUnsignedShort();
            f.skipBytes(2);
            int encryptionType = f.readUnsignedShort();
            if (encryptionType != 0) { pr.drm = true; return pr; }
            if (compression != 1 && compression != 2) { pr.unsupportedCompression = true; return pr; }

            long mobiStart = record0 + 16;
            if (mobiStart + 132 <= len) {
                try {
                    f.seek(mobiStart);
                    byte[] ident = new byte[4];
                    f.readFully(ident);
                    if ("MOBI".equals(new String(ident, StandardCharsets.US_ASCII))) {
                        f.seek(mobiStart + 4);
                        long headerLength = f.readInt() & 0xFFFFFFFFL;
                        f.seek(mobiStart + 128);
                        long exthFlags = f.readInt() & 0xFFFFFFFFL;
                        if ((exthFlags & 0x40) != 0 && headerLength > 0 && headerLength < 10_000) {
                            long exthStart = mobiStart + headerLength;
                            if (exthStart + 12 <= len) {
                                f.seek(exthStart);
                                byte[] exthIdent = new byte[4];
                                f.readFully(exthIdent);
                                if ("EXTH".equals(new String(exthIdent, StandardCharsets.US_ASCII))) {
                                    f.skipBytes(4);
                                    long recCount = f.readInt() & 0xFFFFFFFFL;
                                    long pos = exthStart + 12;
                                    for (long i = 0; i < recCount && i < 500 && pos + 8 <= len; i++) {
                                        f.seek(pos);
                                        long type = f.readInt() & 0xFFFFFFFFL;
                                        long recLen = f.readInt() & 0xFFFFFFFFL;
                                        if (recLen < 8 || pos + recLen > len) break;
                                        if (type == 121) pr.kf8 = true; // KF8-Boundary-EXTH -> Kombi-Datei
                                        pos += recLen;
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            textRecordCount = Math.min(textRecordCount, numRecords - 1);
            int textEncoding = 0;
            if (record0 + 32 <= len) { f.seek(record0 + 28); textEncoding = f.readInt(); } // MOBI-Kopf 0x0C
            java.nio.charset.Charset cs = mobiCharset(textEncoding);
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            for (int rec = 1; rec <= textRecordCount; rec++) {
                int start = offsets[rec];
                int end = offsets[rec + 1];
                if (start < 0 || end <= start || end > len) continue;
                byte[] raw = new byte[end - start];
                f.seek(start);
                f.readFully(raw);
                byte[] piece = (compression == 2) ? decompressPalmDoc(raw) : raw;
                body.write(piece, 0, piece.length);
                if (body.size() > FileExtractors.MAX_CHARS * 4) break;
            }
            String text = new String(body.toByteArray(), cs);
            pr.html = pr.kf8 ? stripTags(text) : text;
        } catch (Throwable t) {
            android.util.Log.w("EdgeTabSearch", "MOBI-Vorschau fehlgeschlagen: " + file.getName(), t);
        }
        return pr;
    }
}
