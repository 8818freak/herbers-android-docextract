package de.herbers.docextract;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;

/** PDF-Textauszug ueber PDFBox-Android (unter libs/ vendort, dessen
 *  Schriftart-/Kodierungs-Ressourcen liegen im Projekt-Ordner assets/).
 *  Titel/Autor aus PDDocumentInformation - fast kostenlos, steht direkt im
 *  Dokument-Kopf, keine Seiten muessen dafuer gerendert werden. */
public final class PdfExtractorHelper {

    private PdfExtractorHelper() {}
    private static volatile boolean inited = false;

    public static void init(Context ctx) {
        if (!inited) {
            synchronized (PdfExtractorHelper.class) {
                if (!inited) {
                    try { PDFBoxResourceLoader.init(ctx.getApplicationContext()); }
                    catch (Throwable ignored) {}
                    inited = true;
                }
            }
        }
    }

    static FileExtractors.Result extract(File f, boolean wantContent) {
        FileExtractors.Result res = new FileExtractors.Result();
        try (PDDocument doc = PDDocument.load(f)) {
            if (doc.isEncrypted()) {
                // Verschluesselte PDFs (Passwort/DRM) bewusst nicht
                // versuchen zu knacken - Bouncy Castle (fuer PDFBox'
                // Entschluesselung noetig) ist absichtlich nicht mit
                // vendort, um die APK nicht weiter aufzublaehen.
                res.drm = true;
                return res;
            }
            PDDocumentInformation info = doc.getDocumentInformation();
            if (info != null) {
                res.title = emptyToNull(info.getTitle());
                res.author = emptyToNull(info.getAuthor());
            }
            if (wantContent) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                res.text = FileExtractors.cap(stripper.getText(doc));
            }
        } catch (Throwable t) {
            android.util.Log.w("EdgeTabSearch", "PDF-Extraktion fehlgeschlagen: " + f.getName(), t);
        }
        return res;
    }

    /** Erste Seite gerendert, klein (fuer die Miniatur in der Trefferliste) -
     *  Skalierung bewusst niedrig, das Ergebnis wird von Thumbnails.java
     *  ohnehin nochmal auf ~200x280px heruntergerechnet. */
    static android.graphics.Bitmap renderCoverBitmap(File f) {
        try (PDDocument doc = PDDocument.load(f)) {
            if (doc.isEncrypted() || doc.getNumberOfPages() == 0) return null;
            return new PDFRenderer(doc).renderImage(0, 0.4f);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Seitenzahl, fuer den Blätter-Betrachter (PreviewActivity). */
    public static int pageCount(File f) {
        try (PDDocument doc = PDDocument.load(f)) {
            return doc.isEncrypted() ? 0 : doc.getNumberOfPages();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Eine einzelne Seite in voller Vorschau-Aufloesung rendern. Oeffnet das
     *  Dokument jedesmal neu (statt eine PDDocument-Instanz ueber mehrere
     *  Seiten hinweg offen zu halten) - fuer normal grosse PDFs schnell genug,
     *  einfacher/robuster als ein zustandsbehaftetes Objekt in der Activity
     *  am Leben zu halten. */
    public static android.graphics.Bitmap renderPage(File f, int pageIndex, float scale) {
        try (PDDocument doc = PDDocument.load(f)) {
            if (doc.isEncrypted() || pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) return null;
            return new PDFRenderer(doc).renderImage(pageIndex, scale);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
