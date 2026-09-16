# LiteRT-LM talks to its native layer by reflection/annotations and Gson.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn com.google.ai.edge.litertlm.**
-keep class com.google.gson.** { *; }

# sherpa-onnx resolves its Kotlin API classes from native code by name — shrinking or renaming
# them turns every call into UnsatisfiedLinkError.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
