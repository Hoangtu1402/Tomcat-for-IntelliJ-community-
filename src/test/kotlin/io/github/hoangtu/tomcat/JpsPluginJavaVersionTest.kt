package io.github.hoangtu.tomcat

import junit.framework.TestCase
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files

/**
 * The external build process (JPS) runs on the *project* JDK when it is Java 11+ (BuildManager), so the plugin's
 * JPS classes must be loadable by Java 11. Otherwise every build of a Java 11 project fails with
 * "Internal error (java.lang.UnsupportedClassVersionError): io/github/hoangtu/tomcat/jps/... (class file version 65.0)".
 */
class JpsPluginJavaVersionTest : TestCase() {
    private val jpsClasses = listOf(
        "io.github.hoangtu.tomcat.jps.WebModelSerializerExtension",
        "io.github.hoangtu.tomcat.jps.WebFacetResourcesBuilder",
        "io.github.hoangtu.tomcat.jps.JpsWebFacetsElement",
        "io.github.hoangtu.tomcat.jps.JpsWebFacetResourcesElement",
        "io.github.hoangtu.tomcat.jps.JpsWebRoot",
    )

    private fun classBytes(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }

    fun testJpsClassesTargetJava11() {
        for (name in jpsClasses) {
            DataInputStream(classBytes(name).inputStream()).use { input ->
                assertEquals(0xCAFEBABE.toInt(), input.readInt())
                input.readUnsignedShort() // minor
                val major = input.readUnsignedShort()
                assertTrue("$name has class file version $major (Java ${major - 44}); JPS on Java 11 needs <= 55", major <= 55)
            }
        }
    }

    /** With TEST_JDK_HOME pointing to a Java 11 JDK, really load the classes on Java 11. */
    fun testJpsClassesLoadOnTestJdk() {
        val jdk = System.getProperty("test.jdk.home") ?: return
        val dir = Files.createTempDirectory("jps-check").toFile()
        for (name in jpsClasses) {
            val file = File(dir, name.replace('.', '/') + ".class")
            file.parentFile.mkdirs()
            file.writeBytes(classBytes(name))
        }
        // dependencies of the JPS classes: the jars on the test classpath (jps-model, jps-builders, util, ...)
        val classpath = System.getProperty("java.class.path")
        val checker = File(dir, "Check.java")
        checker.writeText("""
            public class Check { public static void main(String[] a) throws Exception {
              for (String c : a) Class.forName(c, true, Check.class.getClassLoader()); System.out.println("OK"); } }
        """.trimIndent())
        val javac = ProcessBuilder("$jdk/bin/javac", "-d", dir.path, checker.path).redirectErrorStream(true).start()
        assertEquals(javac.inputStream.bufferedReader().readText(), 0, javac.waitFor())
        val run = ProcessBuilder(listOf("$jdk/bin/java", "-cp", dir.path + File.pathSeparator + classpath, "Check") + jpsClasses)
            .redirectErrorStream(true).start()
        val output = run.inputStream.bufferedReader().readText()
        assertEquals(output, 0, run.waitFor())
        assertTrue(output, output.contains("OK"))
    }
}
