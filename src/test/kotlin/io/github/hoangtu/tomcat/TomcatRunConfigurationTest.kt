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
    }

    fun testRunAndDebugRunnersAreAvailable() {
        val configuration = newConfiguration()
        assertNotNull(com.intellij.execution.runners.ProgramRunner.getRunner(com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID, configuration))
        assertNotNull(com.intellij.execution.runners.ProgramRunner.getRunner(com.intellij.execution.executors.DefaultDebugExecutor.EXECUTOR_ID, configuration))
    }

    fun testContextNames() {
        assertEquals("ROOT", TomcatContexts.descriptorName("/"))
        assertEquals("ROOT", TomcatContexts.descriptorName(""))
        assertEquals("app", TomcatContexts.descriptorName("/app/"))
        assertEquals("a#b", TomcatContexts.descriptorName("/a/b"))
        assertEquals("/demo_war_exploded", TomcatDeployment.defaultContextPath("demo:war exploded"))
    }
}
