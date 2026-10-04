# Room and Hilt supply their own consumer rules.

# ONNX Runtime JNI accesses these Java types; required for R8-minimized Android builds.
-keep class ai.onnxruntime.** { *; }
