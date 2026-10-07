import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.konove"
version = "0.1.0"

// The CLion release the plugin is built, tested and run against. Gradle downloads it.
val clionVersion = "2026.2.3"

kotlin {
    jvmToolchain(25)
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
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("org.jetbrains.plugins.terminal")
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
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
    systemProperty("idea.load.plugins.id", "io.github.konove.notmytodo,com.intellij.mcpServer,org.jetbrains.plugins.terminal")
    // The channel writes a port file per project; tests must not write into the user's own cache.
    systemProperty("notmytodo.cacheDir", layout.buildDirectory.dir("test-cache").get().asFile.path)
}
