@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

group = "io.github.crowded-libs"
version = "0.1.0"

kotlin {
    android {
        namespace = "io.github.crowdedlibs.duks.ga4"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        withHostTest {}
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    wasmJs {
        browser()
    }

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                optIn("kotlin.time.ExperimentalTime")
            }
        }
        commonMain {
            dependencies {
                // Compose Compiler needs runtime on the classpath. Prefer runtime only —
                // foundation/ui come transitively via duks-routing when needed.
                implementation(libs.compose.runtime)
                implementation(libs.duks)
                implementation(libs.duks.routing)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

dokka {
    moduleName = project.name
    dokkaSourceSets {
        named("commonMain")
    }
}

val dokkaHtmlJar = tasks.register<Jar>("dokkaHtmlJar") {
    description = "A HTML Documentation JAR containing Dokka HTML"
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("html-doc")
}

mavenPublishing {
    publishToMavenCentral()

    // Sign only when credentials are present (Central/CI). mavenLocal iteration skips signing.
    val canSign =
        providers.gradleProperty("signing.keyId").orNull != null ||
            providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").orNull != null ||
            providers.environmentVariable("SIGNING_KEY").orNull != null
    if (canSign) {
        signAllPublications()
    }

    coordinates(group.toString(), "duks-ga4", version.toString())

    pom {
        name = project.name
        description = "A Google Analytics 4 library for Kotlin Multiplatform"
        inceptionYear = "2025"
        url = "https://github.com/crowded-libs/duks-ga4/"
        licenses {
            license {
                name = "Apache 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "coreykaylor"
                name = "Corey Kaylor"
                email = "corey@kaylors.net"
            }
        }
        scm {
            url = "https://github.com/crowded-libs/duks-ga4/"
            connection = "scm:git:git://github.com/crowded-libs/duks-ga4.git"
            developerConnection = "scm:git:ssh://git@github.com/crowded-libs/duks-ga4.git"
        }
    }
}
