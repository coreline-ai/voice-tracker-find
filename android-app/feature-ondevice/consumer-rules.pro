# Keep every llama.cpp native entry-point name. The bundled AAR also ships an
# equivalent consumer rule, but this module-level rule protects the summary runtime.
-keepclasseswithmembernames class * {
    native <methods>;
}
