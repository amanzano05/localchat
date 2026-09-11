# LiteRT-LM talks to its native layer by reflection/annotations and Gson.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn com.google.ai.edge.litertlm.**
-keep class com.google.gson.** { *; }
