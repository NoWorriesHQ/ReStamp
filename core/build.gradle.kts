import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: the metadata engine has no Android dependency, so it can be unit-tested
// quickly on any machine and reused by the app.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // Optional: point at a folder of real sample files to run the sample-based tests.
    System.getenv("RESTAMP_SAMPLES")?.let { systemProperty("restamp.samples", it) }
    System.getenv("RESTAMP_OUT")?.let { systemProperty("restamp.out", it) }
    // The sample folders aren't tracked inputs, so name them here to stop a cached result
    // standing in for a run over real files.
    inputs.property("videoCorpus", System.getenv("RESTAMP_VIDEO_CORPUS").orEmpty())
    inputs.property("samples", System.getenv("RESTAMP_SAMPLES").orEmpty())
}
