// Plain Java, no Android. That is the point: the rules can be unit-tested on the JVM in
// milliseconds, and the same class can later be compiled to the browser with TeaVM the way
// match3-engine is. Any Android import added here breaks both.
plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // A suite that runs zero tests must not report success. This module was briefly
    // configured so that it did, and the build still went green.
    testLogging { events("passed", "failed", "skipped") }
    doLast {
        val results = layout.buildDirectory.dir("test-results/test").get().asFile
        val count = results.listFiles { f -> f.name.endsWith(".xml") }?.size ?: 0
        if (count == 0) throw GradleException("no tests were executed")
    }
}
