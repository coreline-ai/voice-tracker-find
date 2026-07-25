# sherpa-onnx JNI reflects over these configuration/result field names.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep every native entry-point name. The bundled llama.cpp AAR also ships an
# equivalent consumer rule, but this module-level rule protects both runtimes.
-keepclasseswithmembernames class * {
    native <methods>;
}
