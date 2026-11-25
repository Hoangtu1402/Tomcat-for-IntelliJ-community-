package io.github.hoangtu.tomcat

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.hoangtu.tomcat.run.TomcatConfigurationType
import io.github.hoangtu.tomcat.run.TomcatContexts
import io.github.hoangtu.tomcat.run.TomcatDeployment
import io.github.hoangtu.tomcat.run.TomcatRunConfiguration
import io.github.hoangtu.tomcat.run.UpdatePolicy
import org.jdom.Element

class TomcatRunConfigurationTest : BasePlatformTestCase() {
    private fun newConfiguration(): TomcatRunConfiguration {
        val factory = TomcatConfigurationType.getInstance().configurationFactories.single()
        return factory.createTemplateConfiguration(project) as TomcatRunConfiguration
    }

    /** A Tomcat run configuration as stored by IntelliJ IDEA Ultimate. */
    fun testReadsUltimateConfiguration() {
        val xml = """
            <configuration name="Tomcat 10.1.34" type="#com.intellij.j2ee.web.tomcat.TomcatRunConfigurationFactory" factoryName="Local"
                           APPLICATION_SERVER_NAME="Tomcat 10.1.34" ALTERNATIVE_JRE_ENABLED="false">
              <option name="OPEN_IN_BROWSER_URL" value="http://localhost:8081/demo_war_exploded/" />
              <option name="UPDATING_POLICY" value="restart-server" />
              <deployment>
                <artifact name="demo:war exploded">
                  <settings>
                    <option name="CONTEXT_PATH" value="/demo_war_exploded" />
                  </settings>
                </artifact>
              </deployment>
              <server-settings>
                <option name="BASE_DIRECTORY_NAME" value="abc" />
              </server-settings>
              <predefined_log_file enabled="true" id="Tomcat" />
              <predefined_log_file enabled="true" id="Tomcat Catalina" />
              <method v="2">
                <option name="Make" enabled="true" />
              </method>
            </configuration>
        """.trimIndent()
        assertEquals("#com.intellij.j2ee.web.tomcat.TomcatRunConfigurationFactory", TomcatConfigurationType.ID)
        val configuration = newConfiguration()
        configuration.readExternal(JDOMUtil.load(xml))
        assertEquals("Tomcat 10.1.34", configuration.serverName)
        assertEquals(8081, configuration.httpPort)
        assertEquals(UpdatePolicy.RESTART, configuration.updatePolicy)
        assertEquals(listOf(TomcatDeployment("demo:war exploded", "/demo_war_exploded")), configuration.deployments)
    }

    fun testRoundTrip() {
        val configuration = newConfiguration()
        configuration.serverName = "Tomcat 9"
        configuration.httpPort = 9090
        configuration.jmxPort = 2099
        configuration.vmOptions = "-Xmx512m -Dfoo=bar"
        configuration.updatePolicy = UpdatePolicy.UPDATE_CLASSES_AND_RESOURCES
        configuration.frameDeactivationPolicy = UpdatePolicy.UPDATE_RESOURCES
        configuration.deployments = listOf(TomcatDeployment("a:war exploded", "/"), TomcatDeployment("b:war", "/b"))
        configuration.showUpdateDialog = false
        configuration.httpsPort = 8443
        configuration.ajpPort = 8009
        configuration.preserveSessions = true
        configuration.clearLogsOnStart = false
        configuration.logDirectory = "/var/log/my-tomcat"
        val element = Element("configuration")
        configuration.writeExternal(element)

        val copy = newConfiguration()
        copy.readExternal(element)
        assertEquals("Tomcat 9", copy.serverName)
        assertEquals(9090, copy.httpPort)
        assertEquals(2099, copy.jmxPort)
        assertEquals("-Xmx512m -Dfoo=bar", copy.vmOptions)
        assertEquals(UpdatePolicy.UPDATE_CLASSES_AND_RESOURCES, copy.updatePolicy)
        assertEquals(UpdatePolicy.UPDATE_RESOURCES, copy.frameDeactivationPolicy)
        assertEquals(configuration.deployments, copy.deployments)
        assertFalse(copy.showUpdateDialog)
        assertEquals(8443, copy.httpsPort)
        assertEquals(8009, copy.ajpPort)
        assertTrue(copy.preserveSessions)
        assertFalse(copy.clearLogsOnStart)
        assertEquals("/var/log/my-tomcat", copy.logDirectory)
    }

    /** Creates every tab of the run configuration editor and checks that reset + apply keeps the values. */
    fun testSettingsEditorsRoundTrip() {
        val source = newConfiguration()
        source.serverName = "Tomcat X"
        source.httpPort = 9091
        source.httpsPort = 9443
        source.jmxPort = 2199
        source.vmOptions = "-Xmx1g"
        source.updatePolicy = UpdatePolicy.UPDATE_RESOURCES
        source.showUpdateDialog = false
        source.preserveSessions = true
        source.clearLogsOnStart = false
        source.logDirectory = "/tmp/tomcat-logs"
        source.deployments = listOf(TomcatDeployment("x:war exploded", "/x"))
        source.envs = com.intellij.execution.configuration.EnvironmentVariablesData.create(mapOf("A" to "1"), true)

        val target = newConfiguration()
        val editors = listOf(
            io.github.hoangtu.tomcat.run.TomcatServerSettingsEditor(project),
            io.github.hoangtu.tomcat.run.TomcatDeploymentSettingsEditor(project),
            io.github.hoangtu.tomcat.run.TomcatLogsSettingsEditor(project),
            io.github.hoangtu.tomcat.run.TomcatStartupSettingsEditor(),
        )
        try {
            for (editor in editors) {
                assertNotNull(editor.component)
                editor.resetFrom(source)
                editor.applyTo(target)
            }
        } finally {
            editors.forEach { com.intellij.openapi.util.Disposer.dispose(it) }
        }
        assertEquals("Tomcat X", target.serverName)
        assertEquals(9091, target.httpPort)
        assertEquals(9443, target.httpsPort)
        assertEquals(0, target.ajpPort)
        assertEquals(2199, target.jmxPort)
        assertEquals("-Xmx1g", target.vmOptions)
        assertEquals(UpdatePolicy.UPDATE_RESOURCES, target.updatePolicy)
        assertFalse(target.showUpdateDialog)
        assertTrue(target.preserveSessions)
        assertFalse(target.clearLogsOnStart)
        assertEquals("/tmp/tomcat-logs", target.logDirectory)
        assertEquals(source.deployments, target.deployments)
        assertEquals(mapOf("A" to "1"), target.envs.envs)
    }

    fun testRunAndDebugRunnersAreAvailable() {
        val configuration = newConfiguration()
        assertNotNull(com.intellij.execution.runners.ProgramRunner.getRunner(com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID, configuration))
        assertNotNull(com.intellij.execution.runners.ProgramRunner.getRunner(com.intellij.execution.executors.DefaultDebugExecutor.EXECUTOR_ID, configuration))
    }

    fun testServicesViewShowsServerAndArtifactNodes() {
        val contributor = com.intellij.execution.services.ServiceViewContributor.CONTRIBUTOR_EP_NAME.extensionList
            .filterIsInstance<io.github.hoangtu.tomcat.run.TomcatServicesContributor>().single()
        val runManager = com.intellij.execution.RunManager.getInstance(project)
        val settings = runManager.createConfiguration("My Tomcat", TomcatConfigurationType.getInstance().configurationFactories.single())
        (settings.configuration as TomcatRunConfiguration).deployments =
            listOf(TomcatDeployment("shop:war exploded", "/shop"), TomcatDeployment("admin:war exploded", "/admin"))
        runManager.addConfiguration(settings)
        try {
            val servers = contributor.getServices(project)
            assertEquals(1, servers.size)
            val server = servers.single()
            val serverPresentation = contributor.getServiceDescriptor(project, server).presentation
            assertEquals("My Tomcat", serverPresentation.presentableText)
            assertNotNull(contributor.getServiceDescriptor(project, server).contentComponent)

            val children = server.getServices(project)
            assertEquals(listOf("shop:war exploded", "admin:war exploded"), children.map { it.deployment.artifactName })
            val child = server.getServiceDescriptor(project, children[0])
            assertEquals("/shop", child.presentation.locationString)
            val actions = child.toolbarActions!!.let { (it as com.intellij.openapi.actionSystem.DefaultActionGroup).getChildActionsOrStubs() }
            assertTrue(actions.any { it.templatePresentation.text == "Redeploy" })
            assertNotNull(child.contentComponent)

            // Regression: the Services tree renders nodes without a read action; reading the artifact model there
            // must not fail ("Read access is allowed from inside read-action only").
            val fromOtherThread = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread<String> {
                val descriptor = server.getServiceDescriptor(project, children[1])
                descriptor.presentation.presentableText + "|" + contributor.getServiceDescriptor(project, server).presentation.presentableText
            }
            assertEquals("admin:war exploded|My Tomcat", fromOtherThread.get(30, java.util.concurrent.TimeUnit.SECONDS))
            val check = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread<Unit> {
                (settings.configuration as TomcatRunConfiguration).let {
                    try { it.checkConfiguration() } catch (_: com.intellij.execution.configurations.RuntimeConfigurationException) {}
                }
            }
            check.get(30, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            runManager.removeConfiguration(settings)
        }
    }

    fun testContextNames() {
        assertEquals("ROOT", TomcatContexts.descriptorName("/"))
        assertEquals("ROOT", TomcatContexts.descriptorName(""))
        assertEquals("app", TomcatContexts.descriptorName("/app/"))
        assertEquals("a#b", TomcatContexts.descriptorName("/a/b"))
        assertEquals("/demo_war_exploded", TomcatDeployment.defaultContextPath("demo:war exploded"))
    }
}
