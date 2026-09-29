# herbers-android-docextract

*(English · [Deutsch](README.de.md))*

Text, metadata and cover-image extraction from document and e-book files for
Android – **no cloud, no network, entirely on-device**. Factored out of the
app **[Sucher](https://github.com/8818freak/Sucher)**.

> Reads file **names AND contents** – text, metadata (title/author/series) and
> cover images – from a range of document and e-book formats, fully offline.

## What's inside

| Class | Purpose |
|---|---|
| `FileExtractors` | Entry point: text + metadata (title/author/series) from TXT/MD/CSV/HTML/XML, EPUB, FB2, DOCX/XLSX/PPTX, DOC/XLS/PPT, **ODT/ODS/ODP** (OpenDocument), PDF, CBZ (ComicInfo.xml), and archives (see `ArchiveExtractor`). Returns `FileExtractors.Result`. |
| `ArchiveExtractor` | Archives (ZIP/7z/TAR incl. `.gz`/`.bz2`/`.xz`, single-file `.gz`/`.bz2`/`.xz`). Makes an archive searchable **by its contents**: its full text is the concatenated text of the documents inside (each entry unpacked and run through `FileExtractors`). One index entry per archive; nested archives are not recursed (bomb guard); entry count/size capped. RAR is name-only (no free, GPL-compatible RAR decompressor exists; repack as ZIP/7z to search RAR contents). |
| `MobiExtractor` | MOBI/AZW/AZW3/PRC: text, metadata, cover, HTML preview (PalmDOC/MOBI6 decompression, KF8 detection). |
| `ComicExtractor` | CBZ: page names, page bytes, cover, ComicInfo.xml. |
| `PdfExtractorHelper` | PDF (PDFBox-Android): text, page count, render page, cover bitmap. Call `init(context)` before use. |
| `LegacyOfficeExtractor` | Legacy Office (.doc/.xls/.ppt) via Apache POI (poi-scratchpad). |
| `Thumbnails` | Cover/thumbnail images from EPUB/MOBI/CBZ/PDF/OOXML covers and image files; storage location via `setStorageDir(File)`. |

## Usage

```java
// Text + metadata
String ext = "epub"; // from the file name
FileExtractors.Result r = FileExtractors.extract(file, ext, /* wantContent= */ true);
String body   = r.text;   // full text (if wantContent)
String title  = r.title;  // from document/book metadata
String author = r.author;
boolean drm   = r.drm;    // detected copy-protected -> name only

// PDF needs a one-time init (PDFBox resources)
PdfExtractorHelper.init(context.getApplicationContext());
int pages = PdfExtractorHelper.pageCount(pdfFile);

// Thumbnails: pick a storage dir (persistent: getFilesDir, evictable: getCacheDir)
Thumbnails.setStorageDir(context.getFilesDir());
Thumbnails.ensure(context, file, ext);   // generate on demand
java.io.File thumb = Thumbnails.fileFor(context, file.getAbsolutePath());
```

## Building it in

Currently intended as a **source module** (apps add it as a Git submodule and
compile `src/`; raw Android SDK build, no Gradle). The `libs/` folder holds the
required third-party JARs.

```
git submodule add https://github.com/8818freak/herbers-android-docextract docextract
# Build: compile this module's src/ plus libs/*.jar; enable multidex for
# PDF/Office (POI/PDFBox are large).
```

Requirements: tested on Android 10+ (API 29). Java 8 language features
(desugaring). PDF/legacy Office need the JARs in `libs/`.

## Third-party libraries (in `libs/`)

This library bundles a few third-party JARs, each under its own OSS license,
all compatible with (L)GPLv3:

- Apache POI, poi-scratchpad, Apache Commons (Collections, Compress, IO, Math),
  Apache Log4j API, SparseBitSet, **PdfBox-Android** – all **Apache License 2.0**
- curvesapi – **BSD-3-Clause**
- **XZ for Java** (`.xz`/`.tar.xz`) – **Public Domain (0BSD)**

Versions, links and the required notices are in
**[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)**; the full license texts also
ship inside each JAR's `META-INF/`.

## License

**GNU Lesser General Public License v3.0 (or later)** – see `LICENSE`. The LGPLv3
builds on the [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html). The library can
be linked from non-GPL apps too, while changes to the library itself stay
copyleft. Copyright © 2026 Mathias Herbers.
