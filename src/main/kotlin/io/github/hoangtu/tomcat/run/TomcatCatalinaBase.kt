package io.github.hoangtu.tomcat.run

import com.intellij.execution.ExecutionException
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.NioFiles
import io.github.hoangtu.tomcat.server.TomcatInstallation
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

object TomcatContexts {
    /** "" and "/" -> "/", "app/" -> "/app" */
    fun normalize(contextPath: String): String {
        val trimmed = contextPath.trim().trim('/')
        return if (trimmed.isEmpty()) "/" else "/$trimmed"
    }

    /** Name of the context descriptor in conf/Catalina/localhost: "/" -> "ROOT", "/a/b" -> "a#b". */
    fun descriptorName(contextPath: String): String {
        val normalized = normalize(contextPath)
        return if (normalized == "/") "ROOT" else normalized.substring(1).replace('/', '#')
    }
}

/** A deployment resolved to a file system location (exploded directory or .war file). */
data class ResolvedDeployment(val contextPath: String, val docBase: String, val artifactName: String)

/**
 * Creates the per-run-configuration CATALINA_BASE directory (like IntelliJ IDEA Ultimate does):
 * a copy of `conf/` with the configured ports and one context descriptor per deployed artifact
 * pointing straight at the artifact output, so nothing is copied into Tomcat's own `webapps`.
 */
object TomcatCatalinaBase {
    fun directoryFor(configuration: TomcatRunConfiguration): Path {
        val project = configuration.project
        val dirName = FileUtil.sanitizeFileName("${project.name}_${project.locationHash}") + "/" + FileUtil.sanitizeFileName(configuration.name)
        return Path.of(PathManager.getSystemPath(), "tomcat-community", dirName)
    }

    /** Where Tomcat writes catalina.*.log, localhost.*.log and access logs. */
    fun logDirectoryFor(configuration: TomcatRunConfiguration): Path {
        if (configuration.logDirectory.isBlank()) return directoryFor(configuration).resolve("logs")
        return try {
            customLogDirectory(configuration)
        } catch (_: java.nio.file.InvalidPathException) {
            directoryFor(configuration).resolve("logs") // reported by checkConfiguration() and as a warning on start
        }
    }

    /** The user-chosen log directory (relative paths are resolved against the project). Throws on an invalid path. */
    @Throws(java.nio.file.InvalidPathException::class)
    private fun customLogDirectory(configuration: TomcatRunConfiguration): Path {
        val path = Path.of(FileUtil.toSystemDependentName(configuration.logDirectory.trim()))
        if (path.isAbsolute) return path
        val projectDir = configuration.project.basePath ?: return path.toAbsolutePath()
        return Path.of(projectDir).resolve(path).normalize()
    }

    private val TOMCAT_LOG_PREFIXES = listOf("catalina.", "localhost.", "localhost_access_log", "manager.", "host-manager.", "admin.")

    /** Removes the log files of the previous run, so every run starts with empty logs. */
    fun clearLogs(configuration: TomcatRunConfiguration) {
        val dir = logDirectoryFor(configuration)
        if (!dir.isDirectory()) return
        val ownDir = configuration.logDirectory.isBlank()
        for (file in dir.listDirectoryEntries()) {
            val name = file.fileName.toString()
            // in a user-chosen directory only touch files Tomcat creates
            if (Files.isRegularFile(file) && (ownDir || TOMCAT_LOG_PREFIXES.any(name::startsWith))) {
                try {
                    Files.delete(file)
                } catch (_: Exception) {
                    // a file may still be locked (Windows); truncate it instead
                    try { Files.newOutputStream(file).close() } catch (_: Exception) {}
                }
            }
        }
    }

    class Prepared(val base: Path, val hostName: String, val engineName: String, val warnings: List<String> = emptyList()) {
        fun contextFile(contextPath: String): Path =
            base.resolve("conf").resolve(engineName).resolve(hostName).resolve(TomcatContexts.descriptorName(contextPath) + ".xml")
    }

    @Throws(ExecutionException::class)
    fun prepare(configuration: TomcatRunConfiguration, installation: TomcatInstallation, deployments: List<ResolvedDeployment>): Prepared {
        val base = directoryFor(configuration)
        try {
            val conf = base.resolve("conf")
            if (conf.exists()) NioFiles.deleteRecursively(conf)
            Files.createDirectories(conf)
            copyDirectory(installation.home.resolve("conf"), conf)
            for (dir in listOf("logs", "temp", "work", "webapps")) Files.createDirectories(base.resolve(dir))

            // Optional log features: they never prevent Tomcat from starting, problems are reported as warnings.
            val warnings = mutableListOf<String>()
            var customLogDir: Path? = null
            if (configuration.logDirectory.isNotBlank()) {
                try {
                    val dir = customLogDirectory(configuration)
                    Files.createDirectories(dir)
                    patchLoggingProperties(conf.resolve("logging.properties"), dir)
                    customLogDir = dir
                } catch (e: Exception) {
                    warnings += "Cannot use log directory '${configuration.logDirectory}' (${e.message}); Tomcat logs are written to ${base.resolve("logs")}"
                }
            }
            if (configuration.clearLogsOnStart) {
                try {
                    clearLogs(configuration)
                } catch (e: Exception) {
                    warnings += "Cannot clear old log files: ${e.message}"
                }
            }

            val serverXml = conf.resolve("server.xml")
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(serverXml.toFile())
            val (engineName, hostName) = patchServerXml(document, configuration, installation, customLogDir)
            serverXml.writeText(serialize(document))

            val prepared = Prepared(base, hostName, engineName, warnings)
            val contextDir = conf.resolve(engineName).resolve(hostName)
            Files.createDirectories(contextDir)
            for (deployment in deployments) {
                prepared.contextFile(deployment.contextPath).writeText(contextXml(deployment.docBase, configuration.preserveSessions))
            }
            return prepared
        } catch (e: ExecutionException) {
            throw e
        } catch (e: Exception) {
            throw ExecutionException("Cannot prepare Tomcat base directory $base: ${e.message}", e)
        }
    }

    private fun contextXml(docBase: String, preserveSessions: Boolean): String {
        val escaped = docBase.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        // An empty Manager pathname disables session persistence across restarts and redeploys.
        val manager = if (preserveSessions) "" else "\n  <Manager pathname=\"\"/>"
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Context docBase=\"$escaped\" reloadable=\"false\">$manager\n</Context>\n"
    }

    /** Points Tomcat's JULI file handlers at [logDir] (default: ${catalina.base}/logs). */
    private fun patchLoggingProperties(file: Path, logDir: Path) {
        if (!Files.isRegularFile(file)) return
        val dir = FileUtil.toSystemIndependentName(logDir.toString())
        val text = Files.readString(file).replace("\${catalina.base}/logs", dir)
        file.writeText(text)
    }

    private fun copyDirectory(from: Path, to: Path) {
        if (!from.isDirectory()) return
        for (entry in from.listDirectoryEntries()) {
            val target = to.resolve(entry.fileName.toString())
            if (entry.isDirectory()) {
                // conf/Catalina/localhost of the installation contains contexts we do not want (unless apps are deployed)
                if (entry.fileName.toString() == "Catalina") continue
                Files.createDirectories(target)
                copyDirectory(entry, target)
            } else {
                Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun Element.childElements(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    }

    /** Returns (engine name, host name). */
    private fun patchServerXml(document: Document, configuration: TomcatRunConfiguration, installation: TomcatInstallation, logDir: Path?): Pair<String, String> {
        val server = document.documentElement
        // The server is stopped by the IDE (process termination triggers Tomcat's shutdown hook): no shutdown port needed.
        server.setAttribute("port", "-1")

        val service = server.childElements("Service").firstOrNull() ?: throw ExecutionException("server.xml: <Service> element not found")
        var http: Element? = null
        var https: Element? = null
        var ajp: Element? = null
        for (connector in service.childElements("Connector")) {
            val protocol = connector.getAttribute("protocol")
            val isAjp = protocol.contains("AJP", ignoreCase = true)
            val isSsl = connector.getAttribute("SSLEnabled").equals("true", ignoreCase = true)
            when {
                isAjp && ajp == null && configuration.ajpPort != 0 -> ajp = connector
                isSsl && https == null && configuration.httpsPort != 0 -> https = connector
                !isAjp && !isSsl && http == null -> http = connector
                else -> service.removeChild(connector)
            }
        }
        val engineElement = service.childElements("Engine").firstOrNull()
        fun newConnector(protocol: String): Element = document.createElement("Connector").also {
            it.setAttribute("protocol", protocol)
            service.insertBefore(it, engineElement)
        }
        (http ?: newConnector("HTTP/1.1").also { it.setAttribute("connectionTimeout", "20000") })
            .setAttribute("port", configuration.httpPort.toString())
        https?.setAttribute("port", configuration.httpsPort.toString())
        if (configuration.httpsPort != 0) http?.setAttribute("redirectPort", configuration.httpsPort.toString())
        if (configuration.ajpPort != 0) {
            val connector = ajp ?: newConnector("AJP/1.3").also {
                it.setAttribute("address", "127.0.0.1")
                if ((installation.majorVersion ?: 9) >= 9) it.setAttribute("secretRequired", "false")
            }
            connector.setAttribute("port", configuration.ajpPort.toString())
        }

        // access logs go to the same (custom) directory as the other logs
        val valves = document.getElementsByTagName("Valve")
        if (logDir != null) for (i in 0 until valves.length) {
            val valve = valves.item(i) as Element
            if (valve.getAttribute("className").endsWith("AccessLogValve")) valve.setAttribute("directory", logDir.toString())
        }

        val engine = service.childElements("Engine").firstOrNull() ?: throw ExecutionException("server.xml: <Engine> element not found")
        val engineName = engine.getAttribute("name").ifEmpty { "Catalina" }
        val defaultHost = engine.getAttribute("defaultHost").ifEmpty { "localhost" }
        val host = engine.childElements("Host").firstOrNull { it.getAttribute("name") == defaultHost }
            ?: engine.childElements("Host").firstOrNull()
            ?: throw ExecutionException("server.xml: <Host> element not found")
        val hostName = host.getAttribute("name").ifEmpty { "localhost" }
        host.setAttribute("autoDeploy", "true")
        host.setAttribute("deployOnStartup", "true")
        if (configuration.deployTomcatApps) {
            host.setAttribute("appBase", installation.home.resolve("webapps").toString())
        } else {
            host.setAttribute("appBase", "webapps") // empty <base>/webapps
        }
        return engineName to hostName
    }

    private fun serialize(document: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        val writer = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }
}
