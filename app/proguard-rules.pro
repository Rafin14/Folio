# Room and Hilt supply their own consumer rules.

# ONNX Runtime JNI accesses these Java types; required for R8-minimized Android builds.
-keep class ai.onnxruntime.** { *; }
-keep class dev.folio.scanner.pdfanalysis.PdfiumNative { *; }

# iText verifies event ownership through Class.getName(); obfuscation breaks PDF opening.
-keepnames class com.itextpdf.** extends com.itextpdf.commons.actions.AbstractITextEvent

# iText loads module DI registrations from a string array via Class.forName.
-keep class com.itextpdf.**.RegisterDefaultDiContainer { *; }

# Xerces loads parser configurations and datatype factories by name when reading PDF XMP.
# Its optional catalog resolver requires xml-resolver, which PDF XMP parsing does not use.
-keep class !org.apache.xerces.util.XMLCatalogResolver,org.apache.xerces.** { *; }
