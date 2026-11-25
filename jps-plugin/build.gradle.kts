// Build-process (JPS) side of the plugin. It is packaged as a separate jar (tomcat-community-jps.jar)
// and loaded into the external build process via <compileServer.plugin/>.
plugins {
    id("java")
    id("org.jetbrains.intellij.platform.module")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

// The external build process (JPS) runs on the project JDK when it is Java 11 or newer
// (BuildManager.MINIMUM_REQUIRED_JPS_BUILD_JAVA_VERSION), so this jar must be loadable by Java 11.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
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
