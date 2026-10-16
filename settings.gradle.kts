plugins {
    // Downloads the JDK that jvmToolchain asks for when the machine does not have it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "not-my-todo"

// The store's core library, built from the sibling checkout until it is published.
includeBuild("../not-my-todo-store") {
    dependencySubstitution {
        substitute(module("io.github.konove:not-my-todo-store-core")).using(project(":core"))
    }
}
