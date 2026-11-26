pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
// Host-Bewertung der Protokollqualität: eigenständiges JVM-Projekt, nutzt die reinen Kotlin-Dateien der App
// (ProtocolGenerator, DateResolver, Vocabulary, Model) und die Host-JNI-Bibliothek (scripts/build_host_llm.sh).
rootProject.name = "protokoll-eval"
