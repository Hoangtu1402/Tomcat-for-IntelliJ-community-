// Build-process (JPS) side of the plugin. It is packaged as a separate jar (tomcat-community-jps.jar)
// and loaded into the external build process via <compileServer.plugin/>.
plugins {
    id("java")
    id("org.jetbrains.intellij.platform.module")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.jar {
    archiveBaseName.set("tomcat-community-jps")
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion")) { useInstaller = false }
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    instrumentCode = false
}
