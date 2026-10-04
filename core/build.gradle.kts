plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin: parsing, money, cost calculations, reports. No Android dependencies,
// so these tests run on the JVM in seconds: ./gradlew :core:test
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // OfficeExportTest reads the office page from the app's assets.
    systemProperty("kr.repo", rootDir.absolutePath)
}
