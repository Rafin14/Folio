# Models & offline OCR

Folio includes its scanner, OCR and PDF layout models. Document detection and text recognition run on the device without a model download or an online OCR service.

## Document detection

**LCNet**, from [DocsaidLab DocAligner](https://github.com/DocsaidLab/DocAligner), detects document corners. OpenCV applies perspective correction. Manual corner adjustment remains available when detection cannot find the document.

DocAligner is licensed under [Apache-2.0](https://github.com/DocsaidLab/DocAligner/blob/main/LICENSE). The model is listed in the upstream [heatmap configuration](https://github.com/DocsaidLab/DocAligner/blob/main/docaligner/heatmap_reg/infer.py).

## Text recognition

Folio uses **PaddleOCR PP-OCRv6_small** detection and recognition models:

- [Official detection model](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx)
- [Official recognition model](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx)

The models use Apache-2.0. The recognition dictionary and configuration are included. PaddleOCR source attribution is preserved in the bundled notices.

OCR can run automatically in the background or on demand. Extracted text is searchable and selectable. Word and phrase search highlights matches and provides wrapping previous/next navigation.

OCR works best with printed English. Handwriting, mathematics, other languages and complex layouts may produce inaccurate results. Review extracted text before using it.

Hardware acceleration depends on device and model compatibility; CPU fallback keeps OCR available. XNNPACK accelerates CPU execution. GPU execution is not guaranteed.

## PDF layout analysis

**PP-DocLayoutV3** identifies page regions for layout-aware OCR, figure extraction and Word conversion. The model comes from [PaddlePaddle](https://huggingface.co/PaddlePaddle/PP-DocLayoutV3) under Apache-2.0.

Layout analysis can require substantial memory. Pages are processed incrementally, and acceleration uses a CPU fallback when necessary.

Word conversion reconstructs supported ruled tables as editable cells. Ambiguous or unruled tables fall back to editable text with a warning. Layout reconstruction is best effort.

## Bundled model files

Paths below are relative to `app/src/main/assets/models/`.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `lcnet100_h_e_bifpn_256_fp32.onnx` | 4,767,987 | `f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e` |
| `ocr/det/inference.onnx` | 9,880,512 | `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e` |
| `ocr/rec/inference.onnx` | 21,159,378 | `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634` |
| `layout/PP-DocLayoutV3.onnx` | 130,502,049 | `d24809294b2f9f1a9a2767043a64df2714b66e5be056887be2233d1117d784f6` |

See [Licenses & credits](THIRD_PARTY.md) and the [bundled notices](../app/src/main/assets/licenses) for attribution.
