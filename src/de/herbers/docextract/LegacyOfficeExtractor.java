package de.herbers.docextract;

import java.io.File;
import java.io.FileInputStream;

/**
 * Text aus alten MS-Office-Formaten (.doc/.xls/.ppt, binaeres OLE2-Format
 * vor Office 2007) - dafuer, anders als bei DOCX/XLSX/PPTX, ueber Apache POI
 * (Module "poi" + "poi-scratchpad", unter libs/ vendort). Das Binaerformat
 * von Hand zu parsen (Piece-Table bei Word etc.) waere unverhaeltnismaessig
 * riskant; POI ist hier die einzige im Projekt vendorte "schwere"
 * Bibliothek neben PDFBox.
 *
 * Titel/Autor kommen fuer alle drei Formate einheitlich aus dem
 * "SummaryInformation"-Stream, den jede OLE2-Office-Datei traegt (POIs
 * eigenes hpsf-Paket) - unabhaengig davon, ob der jeweilige Text-Extractor
 * selbst Metadaten kann (QuickButCruddyTextExtractor fuer PPT z.B. nicht
 * direkt). Billig (kein Text-Parsing noetig), immer versucht.
 *
 * Laeuft komplett in eigenen try/catch(Throwable) - POI wurde nicht fuer
 * Android gebaut. Fuer .ppt bewusst QuickButCruddyTextExtractor statt des
 * "richtigen" PowerPointExtractor (reine Text-Extraktion ohne Folien-
 * Rendering, damit ohne java.awt-Beruehrung - Android hat kein AWT); .doc/
 * .xls sind reine Textlese-Pfade und sollten unproblematisch sein, aber
 * auch hier faellt im Zweifel diese eine Datei auf "nur Name/Metadaten"
 * zurueck statt die ganze Indizierung zu reissen. Noch nicht auf einem
 * echten Geraet getestet.
 */
final class LegacyOfficeExtractor {

    private LegacyOfficeExtractor() {}

    static FileExtractors.Result extract(File f, String ext, boolean wantContent) {
        FileExtractors.Result res = new FileExtractors.Result();
        readSummaryInfo(f, res);
        if (!wantContent) return res;
        try (FileInputStream in = new FileInputStream(f)) {
            String text;
            switch (ext) {
                case "doc":
                    text = new org.apache.poi.hwpf.extractor.WordExtractor(in).getText();
                    break;
                case "xls":
                    text = new org.apache.poi.hssf.extractor.ExcelExtractor(
                            new org.apache.poi.hssf.usermodel.HSSFWorkbook(in)).getText();
                    break;
                case "ppt":
                    // QuickButCruddyTextExtractor statt PowerPointExtractor (in
                    // dieser POI-Version aus dem Paket entfernt) - passt fuer
                    // unseren Zweck ohnehin besser: reine Text-Extraktion ohne
                    // Formen/Folien-Rendering, also ohne java.awt-Beruehrung
                    // (Android hat kein AWT).
                    text = new org.apache.poi.hslf.extractor.QuickButCruddyTextExtractor(in).getTextAsString();
                    break;
                default:
                    return res;
            }
            res.text = FileExtractors.cap(text);
        } catch (Throwable t) {
            android.util.Log.w("EdgeTabSearch", "POI-Extraktion fehlgeschlagen: " + f.getName(), t);
        }
        return res;
    }

    private static void readSummaryInfo(File f, FileExtractors.Result res) {
        try (FileInputStream fis = new FileInputStream(f);
             org.apache.poi.poifs.filesystem.POIFSFileSystem fsys =
                     new org.apache.poi.poifs.filesystem.POIFSFileSystem(fis)) {
            org.apache.poi.poifs.filesystem.DirectoryEntry root = fsys.getRoot();
            if (!root.hasEntry(org.apache.poi.hpsf.SummaryInformation.DEFAULT_STREAM_NAME)) return;
            org.apache.poi.hpsf.SummaryInformation si = (org.apache.poi.hpsf.SummaryInformation)
                    org.apache.poi.hpsf.PropertySetFactory.create(root, org.apache.poi.hpsf.SummaryInformation.DEFAULT_STREAM_NAME);
            res.title = emptyToNull(si.getTitle());
            res.author = emptyToNull(si.getAuthor());
        } catch (Throwable ignored) {
            // Kein SummaryInformation-Stream, oder ein aelteres/kaputtes
            // Dokument ohne - Titel/Autor bleiben dann einfach leer.
        }
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
