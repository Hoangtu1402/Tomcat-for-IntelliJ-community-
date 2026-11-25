package io.github.hoangtu.tomcat.run

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Java version of the JDK/JRE used to run Tomcat (project SDK or the alternative JRE of the configuration). */
object TomcatJdk {
    fun featureVersion(configuration: TomcatRunConfiguration): Int? {
        if (configuration.useAlternativeJre && configuration.alternativeJrePath.isNotBlank()) {
            val byName = ProjectJdkTable.getInstance().findJdk(configuration.alternativeJrePath)
            return if (byName != null) featureVersion(byName) else featureVersionOfHome(configuration.alternativeJrePath)
        }
        return ProjectRootManager.getInstance(configuration.project).projectSdk?.let(::featureVersion)
    }

    fun featureVersion(sdk: Sdk): Int? =
        JavaSdk.getInstance().getVersion(sdk)?.maxLanguageLevel?.feature()
            ?: sdk.homePath?.let(::featureVersionOfHome)

    /** Reads JAVA_VERSION from `<home>/release` ("1.8.0_402" -> 8, "11.0.22" -> 11). */
    fun featureVersionOfHome(home: String): Int? = try {
        val release = Path.of(home).resolve("release")
        if (!Files.isRegularFile(release)) null
        else {
            val props = Properties().apply { Files.newBufferedReader(release).use(::load) }
            props.getProperty("JAVA_VERSION")?.trim('"')?.let(::parse)
        }
    } catch (_: Exception) {
        null
    }

    fun parse(version: String): Int? {
        val parts = version.split('.', '_', '-', '+')
        val first = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
    }

    /** Error message when Tomcat cannot run on the selected JDK, null when compatible or unknown. */
    fun incompatibility(configuration: TomcatRunConfiguration): String? {
        val installation = configuration.installation ?: return null
        val required = installation.requiredJavaVersion ?: return null
        val actual = featureVersion(configuration) ?: return null
        if (actual >= required) return null
        return "Tomcat ${installation.version} requires Java $required or newer, but the JRE is Java $actual. " +
            "Use Tomcat ${if (actual >= 11) "10.1" else "9.0"} or select a newer JRE on the Server tab."
    }
}
