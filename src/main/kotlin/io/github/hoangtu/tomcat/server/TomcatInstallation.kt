package io.github.hoangtu.tomcat.server

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile

/** A local Tomcat distribution (CATALINA_HOME). */
data class TomcatInstallation(val home: Path) {
    val bootstrapJar: Path get() = home.resolve("bin/bootstrap.jar")
    val juliJar: Path get() = home.resolve("bin/tomcat-juli.jar")
    val catalinaJar: Path get() = home.resolve("lib/catalina.jar")

    val isValid: Boolean get() = Files.isRegularFile(bootstrapJar) && Files.isRegularFile(catalinaJar) && Files.isDirectory(home.resolve("conf"))

    /** e.g. "10.1.34" (read from lib/catalina.jar!/org/apache/catalina/util/ServerInfo.properties) */
    val version: String? by lazy { readServerInfo()?.getProperty("server.number")?.let(::trimBuildNumber) ?: readServerInfo()?.getProperty("server.info")?.substringAfter('/') }

    val majorVersion: Int? get() = version?.substringBefore('.')?.toIntOrNull()

    /** Tomcat 10+ uses the jakarta.* namespace. */
    val isJakarta: Boolean get() = (majorVersion ?: 10) >= 10

    val defaultName: String get() = "Tomcat " + (version ?: home.fileName.toString())

    private fun trimBuildNumber(number: String): String {
        val parts = number.split('.')
        return if (parts.size == 4 && parts[3] == "0") parts.take(3).joinToString(".") else number
    }

    private fun readServerInfo(): Properties? = try {
        if (!Files.isRegularFile(catalinaJar)) null
        else ZipFile(catalinaJar.toFile()).use { zip ->
            val entry = zip.getEntry("org/apache/catalina/util/ServerInfo.properties") ?: return null
            Properties().apply { zip.getInputStream(entry).use(::load) }
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        fun validate(home: Path): String? = when {
            !Files.isDirectory(home) -> "Directory does not exist"
            !Files.isRegularFile(home.resolve("bin/bootstrap.jar")) -> "bin/bootstrap.jar not found: this is not a Tomcat home directory"
            !Files.isRegularFile(home.resolve("lib/catalina.jar")) -> "lib/catalina.jar not found: this is not a Tomcat home directory"
            else -> null
        }
    }
}
