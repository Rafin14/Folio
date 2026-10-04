# Bundled models and offline OCR

All required inference models are included in `app/src/main/assets/models/`. They are intentional runtime assets, not caches or captured documents. Release preparation verified their public upstream identity and preserved licenses.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `lcnet100_h_e_bifpn_256_fp32.onnx` | 4,767,987 | `f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e` |
| `ocr/det/inference.onnx` | 9,880,512 | `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e` |
| `ocr/rec/inference.onnx` | 21,159,378 | `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634` |

## Scanner

LCNet uses ONNX Runtime Android **1.30.0**, preferring XNNPACK with CPU fallback. Input is BGR float `[1,3,256,256]`, normalized to `[0,1]`; output is four `[128,128]` corner heatmaps. OpenCV postprocessing finds/order corners and performs perspective correction. No corners means manual adjustment remains available.

The model's SHA-256 matched a fresh download from the public model ID in [DocAligner's heatmap inference configuration](https://github.com/DocsaidLab/DocAligner/blob/main/docaligner/heatmap_reg/infer.py). That verifies the previously owner-supplied file's upstream identity. DocAligner is [Apache-2.0](https://github.com/DocsaidLab/DocAligner/blob/main/LICENSE); its license and attribution are bundled. Training-data provenance is not independently audited here.

## OCR

Folio uses official **PaddleOCR PP-OCRv6_small** detection and recognition ONNX exports with the supplied YAML configuration and recognition character dictionary. Both weight SHA-256 hashes match the published Hugging Face LFS identifiers in the official repositories:

- [PP-OCRv6_small_det_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx)
- [PP-OCRv6_small_rec_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx)

The corresponding official model cards specify Apache-2.0. `characters.json` is derived from the recognition YAML dictionary with the upstream final space entry. Selected Android preprocessing/postprocessing primitives retain PaddleOCR copyright/license headers; their source revision is recorded in the bundled notice.

OCR runs entirely on-device after installation. Automatic background OCR is optional; users can request extraction on demand. Room stores text, regions, model/revision metadata and a local FTS index. Library search uses that index; the extracted-text viewer provides case-insensitive word/phrase highlights and wrapping previous/next navigation. Edits invalidate stale OCR results.

The runtime requests NNAPI on eligible Android devices and retains CPU fallback for unsupported initialization/inference. Provider availability is not proof that every node executed on an accelerator. XNNPACK is a CPU provider. GPU is **not guaranteed**, and forcing an incompatible backend would risk OCR correctness. Verbose profiling belongs to the test/debug infrastructure.

Printed English recognition is covered by the automated sample test. No guarantee is made for handwriting, mathematics, Bengali, other languages or every document layout. OCR regions do not imply searchable-PDF text-layer export.

Do not substitute arbitrary exports/dictionaries by filename alone. If obtaining the same models separately, use the upstream sources above and verify the hashes and tensor/config compatibility before replacing assets.

