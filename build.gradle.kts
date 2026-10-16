import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.konove"
version = "0.3.0"

// The CLion release the plugin is built, tested and run against. Gradle downloads it.
val clionVersion = "2026.2.3"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Without this the compiler copies every default method of a platform interface into the class
        // that implements it, and the plugin verifier counts the deprecated and experimental ones as used.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        clion(clionVersion)
        bundledPlugin("org.jetbrains.plugins.terminal")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
    // The IDE has its own Kotlin standard library and gson; the plugin must not bring a second copy.
    implementation("io.github.konove:not-my-todo-store-core:0.1.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains", module = "annotations")
        exclude(group = "com.google.code.gson")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    signing {
        // The key and certificate stay out of the repo; signPlugin reads them from the environment.
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    pluginVerification {
        ides {
            // The plugin depends only on the platform, so check it against an IDE other than the one it is built on.
            create(IntelliJPlatformType.CLion, clionVersion)
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

tasks.test {
    // CLion's bundled C++ backend plugins break light test fixtures; tests need only the platform.
    systemProperty("idea.load.plugins.id", "io.github.konove.notmytodo,org.jetbrains.plugins.terminal")
    // The channel writes a port file per project; tests must not write into the user's own cache.
    systemProperty("notmytodo.cacheDir", layout.buildDirectory.dir("test-cache").get().asFile.path)
    // Nor into the user's own store.
    systemProperty("notmytodo.storeRoot", layout.buildDirectory.dir("test-store").get().asFile.path)
}
