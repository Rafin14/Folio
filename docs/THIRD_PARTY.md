# Licenses and credits

Folio's project license is [GNU AGPL v3](../LICENSE). Third-party components retain their respective licenses and attribution.

| Component | License / source |
| --- | --- |
| iText Community Android 9.8.0 | AGPL v3; applicable kernel/io/commons notices under [assets/licenses](../app/src/main/assets/licenses). Five Android corresponding-source JARs are included under [third-party-sources](../third-party-sources). |
| DocAligner LCNet | Apache-2.0; [upstream](https://github.com/DocsaidLab/DocAligner), matching weights and provenance described in [MODELS.md](MODELS.md). |
| PaddleOCR PP-OCRv6_small | Apache-2.0; official PaddlePaddle model cards and selected Android source attribution in [MODELS.md](MODELS.md) and the bundled PaddleOCR notice. |
| PP-DocLayoutV3 | Apache-2.0; [official model card](https://huggingface.co/PaddlePaddle/PP-DocLayoutV3). Supplied IR10 export provenance/checksum in [MODELS.md](MODELS.md) and bundled notice. |
| PDFium / PdfiumAndroidKt 2.0.3 | BSD / Apache-2.0; [PDFium](https://pdfium.googlesource.com/pdfium/) and [Android binding](https://github.com/johngray1965/PdfiumAndroidKt). License and upstream component notices bundled in assets. |
| ONNX Runtime | MIT, with third-party notices included in assets. |
| OpenCV | Apache-2.0; [upstream license](https://github.com/opencv/opencv/blob/master/LICENSE). |
| AndroidX, Material, Hilt and Google Android libraries | Resolved via Gradle; retain their respective upstream licenses when redistributing binaries. |
| Bouncy Castle, Jackson, SLF4J, Xerces, XML APIs | Applicable license/notice texts retained in assets. |
| MakeACopy photographs | Apache-2.0; upstream URL, attribution and license retained in [photograph attribution](../app/src/androidTest/assets/detection/README.md). |
| Gradle wrapper | Apache-2.0; wrapper headers preserved and distribution SHA-256 pinned. |

PDF processing retains iText producer metadata and license notices. When distributing APKs, provide complete corresponding Folio source, build scripts and applicable dependency source/notices as required by their licenses; do not strip them from the source archive.
