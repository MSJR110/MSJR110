import java.util.Properties

plugins {
    kotlin("jvm") version "2.2.21"
}

kotlin {
    jvmToolchain(17)
}

val app = file("../app/src/main/java/dev/msjr/prism")

// android.jar is only needed to compile the app sources; at run time a tiny shim stands in for RectF
val androidJar: File? = run {
    val local = Properties().apply { file("../local.properties").takeIf { it.exists() }?.reader()?.use { load(it) } }
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: local.getProperty("sdk.dir")
    sdk?.let { s -> file("$s/platforms").listFiles()?.filter { File(it, "android.jar").exists() }?.maxByOrNull { it.name }?.let { File(it, "android.jar") } }
}

sourceSets {
    main {
        kotlin.srcDirs("src/shim/kotlin")
        kotlin.srcDir(app.resolve("text"))
        kotlin.srcDir(app.resolve("keyboard"))
        kotlin.srcDir(app.resolve("data"))
    }
    // the whole app, compiled against android.jar: a quick "does it build" check without the Android plugin
    // (./gradlew compileAppKotlin)
    create("app") {
        kotlin.srcDir("../app/src/main/java")
    }
}

dependencies {
    androidJar?.let { compileOnly(files(it)); testRuntimeOnly(files(it)); "appCompileOnly"(files(it)) }
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    workingDir = projectDir
    maxHeapSize = "8g"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
    testLogging { showStandardStreams = true; events("failed") }
    outputs.upToDateWhen { false }
    // the corpus-heavy tool only runs when asked: -Pprism.corpus=<folder with fa.txt.gz / en.txt>
    systemProperty("prism.corpus", (project.findProperty("prism.corpus") as String?) ?: "")
    systemProperty("prism.bench", (project.findProperty("prism.bench") as String?) ?: "")
    systemProperty("prism.cldr", (project.findProperty("prism.cldr") as String?) ?: "")
}
