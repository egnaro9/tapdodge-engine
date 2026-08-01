// Compiles the engine to JavaScript with TeaVM for the browser build.
//
// Its own module on purpose: :engine must keep its no-dependencies-outside-the-JDK
// guarantee — that is what lets the Android app and this share one compiled rulebook —
// so TeaVM, JSO and the @JSExport wrapper stay here.
plugins {
    id("java")
    id("org.teavm") version "0.15.0"
}


java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

dependencies {
    implementation(project(":engine"))
    implementation(teavm.libs.jso)
}

teavm {
    js {
        // Library mode: everything is exported via @JSExport and there is no main(),
        // but the plugin still requires mainClass, so point it at the wrapper.
        mainClass = "com.seraphlight.tapdodgerush.web.TapDodgeJs"
        moduleType = org.teavm.gradle.api.JSModuleType.ES2015
        targetFileName = "tapdodge.js"
        obfuscated = true
        sourceMap = false
        strict = true
    }
}

// One command for the cross-compiler check: node drives the TeaVM artifact through the same
// input plan the JVM test wrote, and the traces are diffed.
//
// It depends on :engine:test rather than duplicating the run, because that test is what emits
// inputs.json and jvm-trace.json. Wiring it this way means the browser side cannot be checked
// against a stale JVM trace.
val parityCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Assert the JVM and TeaVM builds produce the same game from the same seed"

    dependsOn(":engine:test", "generateJavaScript")

    val parityDir = project(":engine").layout.buildDirectory.dir("parity")
    val js = layout.buildDirectory.file("generated/teavm/js/tapdodge.js")
    val trace = parityDir.map { it.file("js-trace.json") }

    inputs.file(js)
    inputs.file(parityDir.map { it.file("inputs.json") })
    inputs.file(parityDir.map { it.file("jvm-trace.json") })
    inputs.files(rootProject.files("tools/browser_trace.mjs", "tools/compare_trace.mjs"))
    outputs.file(trace)

    commandLine("bash", "-c",
        "node '${rootDir}/tools/browser_trace.mjs' '${js.get().asFile}' " +
        "'${parityDir.get().file("inputs.json").asFile}' > '${trace.get().asFile}' && " +
        "node '${rootDir}/tools/compare_trace.mjs' " +
        "'${parityDir.get().file("jvm-trace.json").asFile}' '${trace.get().asFile}'")
}

tasks.named("check") { dependsOn(parityCheck) }
