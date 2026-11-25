plugins {
    kotlin("jvm") version "2.3.0"
    application
}
repositories { mavenCentral() }
dependencies { implementation("org.json:json:20240303") }
val app = "../../../app/src/main/java"
sourceSets {
    main {
        kotlin {
            srcDir("src")
            srcDir(app)
            include("de/protokollki/eval/**", "de/protokollki/hoststub/**",
                "de/protokollki/engine/ProtocolGenerator.kt", "de/protokollki/engine/DateResolver.kt",
                "de/protokollki/engine/Vocabulary.kt", "de/protokollki/data/Model.kt")
        }
    }
}
application { mainClass.set("de.protokollki.eval.MainKt") }
tasks.named<JavaExec>("run") { workingDir = rootDir.resolve("../../..") }
