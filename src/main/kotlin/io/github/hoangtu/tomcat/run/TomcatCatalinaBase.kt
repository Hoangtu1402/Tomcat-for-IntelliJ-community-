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

    class Prepared(val base: Path, val hostName: String, val engineName: String) {
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

            val serverXml = conf.resolve("server.xml")
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(serverXml.toFile())
            val (engineName, hostName) = patchServerXml(document, configuration, installation)
            serverXml.writeText(serialize(document))

            val prepared = Prepared(base, hostName, engineName)
            val contextDir = conf.resolve(engineName).resolve(hostName)
            Files.createDirectories(contextDir)
            for (deployment in deployments) {
                prepared.contextFile(deployment.contextPath).writeText(contextXml(deployment.docBase))
            }
            return prepared
        } catch (e: ExecutionException) {
            throw e
        } catch (e: Exception) {
            throw ExecutionException("Cannot prepare Tomcat base directory $base: ${e.message}", e)
        }
    }

    private fun contextXml(docBase: String): String {
        val escaped = docBase.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Context docBase=\"$escaped\" reloadable=\"false\"/>\n"
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
    private fun patchServerXml(document: Document, configuration: TomcatRunConfiguration, installation: TomcatInstallation): Pair<String, String> {
        val server = document.documentElement
        // The server is stopped by the IDE (process termination triggers Tomcat's shutdown hook): no shutdown port needed.
        server.setAttribute("port", "-1")
        // JmxRemoteLifecycleListener etc. are not used; JMX is configured through system properties.

        val service = server.childElements("Service").firstOrNull() ?: throw ExecutionException("server.xml: <Service> element not found")
        var httpConnectorFound = false
        for (connector in service.childElements("Connector")) {
            val protocol = connector.getAttribute("protocol")
            val isAjp = protocol.contains("AJP", ignoreCase = true)
            val isSsl = connector.getAttribute("SSLEnabled").equals("true", ignoreCase = true)
            if (!httpConnectorFound && !isAjp && !isSsl) {
                connector.setAttribute("port", configuration.httpPort.toString())
                httpConnectorFound = true
            } else {
                service.removeChild(connector)
            }
        }
        if (!httpConnectorFound) {
            val connector = document.createElement("Connector")
            connector.setAttribute("port", configuration.httpPort.toString())
            connector.setAttribute("protocol", "HTTP/1.1")
            connector.setAttribute("connectionTimeout", "20000")
            val engineElement = service.childElements("Engine").firstOrNull()
            service.insertBefore(connector, engineElement)
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
