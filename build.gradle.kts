import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":jps-plugin"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        // IntelliJ IDEA Community Edition 2025.3
        intellijIdeaCommunity(providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.hoangtu.tomcat-community"
        name = "Tomcat & WAR Support for Community"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

tasks.test {
    // Optional: path to a Tomcat installation for the end-to-end launch test (skipped when not set)
    providers.environmentVariable("TOMCAT_HOME").orNull?.let { systemProperty("tomcat.home", it) }
}
