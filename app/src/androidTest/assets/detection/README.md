# Captured-image regression fixtures

Two unchanged photographs from the MakeACopy project, Apache License 2.0:
https://github.com/egdels/makeacopy/tree/01bebd394b9dd6f3a692f28aea7c0638085eb4da/app/src/androidTest/assets/instrumented_test_data

- `table-paper.jpg`: `20251007_183138.jpg`, white printed paper on a wooden
  table with perspective and uneven illumination.
- `striped-card.jpg`: `sample_20260124_174305_846_original.jpg`, colored printed
  card, rotated on a cluttered striped surface with an imperfect corner.

Attribution: MakeACopy contributors (egdels/makeacopy). The project's Apache
license is reproduced in LICENSE.txt. No upstream processing code was copied.
Images are included in the test APK only; Folio does not ship sample documents.
Corner references in DetectionTest are approximate manual annotations made for
this regression, not official upstream ground truth. Exposure/contrast variants
are simulated transformations of these same two photographs, not new captures.
