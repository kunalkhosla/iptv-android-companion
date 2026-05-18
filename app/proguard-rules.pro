# Kept minimal — debug builds don't minify; release will be tuned
# once we have a real release build. The compose / retrofit /
# kotlinx-serialization plugins ship their own rules via consumer
# proguard files, so we don't need to repeat them here.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
