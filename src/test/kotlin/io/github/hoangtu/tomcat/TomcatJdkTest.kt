package io.github.hoangtu.tomcat

import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.hoangtu.tomcat.run.TomcatConfigurationType
import io.github.hoangtu.tomcat.run.TomcatJdk
import io.github.hoangtu.tomcat.run.TomcatRunConfiguration
import io.github.hoangtu.tomcat.server.TomcatCommunitySettings
import io.github.hoangtu.tomcat.server.TomcatInstallation
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TomcatJdkTest : BasePlatformTestCase() {
    private fun fakeTomcat(version: String): Path {
        val home = Files.createTempDirectory("tomcat-$version")
        Files.createDirectories(home.resolve("bin"))
        Files.createDirectories(home.resolve("conf"))
        Files.createDirectories(home.resolve("lib"))
        Files.write(home.resolve("bin/bootstrap.jar"), ByteArray(0))
        ZipOutputStream(Files.newOutputStream(home.resolve("lib/catalina.jar"))).use { zip ->
            zip.putNextEntry(ZipEntry("org/apache/catalina/util/ServerInfo.properties"))
            zip.write("server.info=Apache Tomcat/$version\nserver.number=$version.0\n".toByteArray())
            zip.closeEntry()
        }
        return home
    }

    private fun fakeJre(javaVersion: String): Path {
        val home = Files.createTempDirectory("jre-$javaVersion")
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"$javaVersion\"\n")
        return home
    }

    private fun configuration(tomcat: String, java: String): TomcatRunConfiguration {
        val configuration = TomcatConfigurationType.getInstance().configurationFactories.single()
            .createTemplateConfiguration(project) as TomcatRunConfiguration
        configuration.serverName = TomcatCommunitySettings.getInstance().register(fakeTomcat(tomcat))
        configuration.useAlternativeJre = true
        configuration.alternativeJrePath = fakeJre(java).toString()
        return configuration
    }

    fun testRequiredJavaVersions() {
        assertEquals(17, TomcatInstallation.requiredJavaVersion("11.0.29"))
        assertEquals(11, TomcatInstallation.requiredJavaVersion("10.1.62"))
        assertEquals(8, TomcatInstallation.requiredJavaVersion("10.0.27"))
        assertEquals(8, TomcatInstallation.requiredJavaVersion("9.0.124"))
        assertEquals(7, TomcatInstallation.requiredJavaVersion("8.5.100"))
        assertEquals(11, TomcatJdk.parse("11.0.22"))
        assertEquals(8, TomcatJdk.parse("1.8.0_402"))
        assertEquals(17, TomcatJdk.parse("17"))
    }

    fun testTomcat11OnJava11IsRejected() {
        val configuration = configuration("11.0.29", "11.0.22")
        try {
            configuration.checkConfiguration()
            fail("expected an error")
        } catch (e: RuntimeConfigurationError) {
            assertTrue(e.message, e.message!!.contains("requires Java 17"))
        }
    }

    fun testTomcat9And101OnJava11AreAccepted() {
        for (tomcat in listOf("9.0.124", "10.1.62")) {
            val configuration = configuration(tomcat, "11.0.22")
            assertNull(TomcatJdk.incompatibility(configuration))
            try {
                configuration.checkConfiguration()
            } catch (_: RuntimeConfigurationWarning) {
                // "No artifacts marked for deployment" is expected here
            }
        }
    }
}
