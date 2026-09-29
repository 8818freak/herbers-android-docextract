# herbers-android-docextract

Textextraktion, Metadaten und Titelbilder aus Dokument- und E-Book-Dateien für
Android – ohne Cloud, ohne Netzwerk, alles auf dem Gerät. Herausgelöst aus der
App **[Sucher](https://github.com/8818freak/Sucher)**.

> Reads file **names AND contents** – text, metadata (title/author/series) and
> cover images – from a range of document and e-book formats, fully offline.

## Was drin ist

| Klasse | Zweck |
|---|---|
| `FileExtractors` | Einstiegspunkt: Text + Metadaten (Titel/Autor/Serie) aus TXT/MD/CSV/HTML/XML, EPUB, FB2, DOCX/XLSX/PPTX, DOC/XLS/PPT, PDF, CBZ (ComicInfo.xml). Liefert `FileExtractors.Result`. |
| `MobiExtractor` | MOBI/AZW/AZW3/PRC: Text, Metadaten, Cover, HTML-Vorschau (PalmDOC/MOBI6-Dekompression, KF8-Erkennung). |
| `ComicExtractor` | CBZ: Seitennamen, Seiten-Bytes, Cover, ComicInfo.xml. |
| `PdfExtractorHelper` | PDF (PDFBox-Android): Text, Seitenzahl, Seite rendern, Cover-Bitmap. Vor Gebrauch `init(context)` aufrufen. |
| `LegacyOfficeExtractor` | Altes Office (.doc/.xls/.ppt) via Apache POI (poi-scratchpad). |
| `Thumbnails` | Titel-/Vorschaubilder aus EPUB/MOBI/CBZ/PDF/OOXML-Cover und Bilddateien; Ablageort per `setStorageDir(File)` wählbar. |

## Nutzung (Beispiel)

```java
// Text + Metadaten
String ext = "epub"; // aus dem Dateinamen
FileExtractors.Result r = FileExtractors.extract(file, ext, /* wantContent= */ true);
String body   = r.text;        // Volltext (falls wantContent)
String title  = r.title;       // aus Dokument-/Buch-Metadaten
String author = r.author;
boolean drm   = r.drm;         // erkannt kopiergeschützt -> nur Name

// PDF benötigt einmalige Initialisierung (PDFBox-Ressourcen)
PdfExtractorHelper.init(context.getApplicationContext());
int pages = PdfExtractorHelper.pageCount(pdfFile);

// Titelbilder: Ablageordner setzen (dauerhaft: getFilesDir, räumbar: getCacheDir)
Thumbnails.setStorageDir(context.getFilesDir());
Thumbnails.ensure(context, file, ext);          // bei Bedarf erzeugen
java.io.File thumb = Thumbnails.fileFor(context, file.getAbsolutePath());
```

## Einbinden

Aktuell als **Quell-Modul** gedacht (die Sucher-Apps binden es als
Git-Submodul ein und kompilieren `src/` mit). Die `libs/` enthalten die
benötigten Drittanbieter-JARs.

```
git submodule add https://github.com/8818freak/herbers-android-docextract common-docextract
# Build: src/ dieses Moduls plus libs/*.jar mitkompilieren; für PDF/Office
# multidex aktivieren (POI/PDFBox sind groß).
```

Voraussetzungen: getestet auf Android 10+ (API 29). Java-8-Sprachfeatures
(Desugaring). Für PDF/altes Office werden die JARs in `libs/` benötigt.

## Drittanbieter-Bibliotheken (in `libs/`)

Unter jeweils eigener Open-Source-Lizenz, mit (L)GPLv3 kombinierbar:

- Apache POI, poi-scratchpad, Apache Commons (Collections, Compress, IO, Math),
  Apache Log4j API, SparseBitSet, **PDFBox-Android** – alle **Apache License 2.0**
- curvesapi – **BSD**

Die vollständigen Lizenztexte liegen den jeweiligen JARs bei.

## Lizenz

**GNU Lesser General Public License v3.0 (oder später)** – siehe `LICENSE`.
Die LGPLv3 baut auf der [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) auf.
Damit lässt sich diese Bibliothek auch aus nicht-GPL-Apps einbinden; Änderungen
an der Bibliothek selbst bleiben copyleft.

Copyright © 2026 Mathias Herbers.
