package io.github.hoangtu.tomcat

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.hoangtu.tomcat.web.WebFacetConfiguration

class WebFacetConfigurationTest : BasePlatformTestCase() {
    /** Facet configuration as written by IntelliJ IDEA Ultimate. */
    private val ultimateXml = """
        <configuration>
          <descriptors>
            <deploymentDescriptor name="web.xml" url="file:///work/demo/src/main/webapp/WEB-INF/web.xml" />
          </descriptors>
          <webroots>
            <root url="file:///work/demo/src/main/webapp" relative="/" />
            <root url="file:///work/demo/extra" relative="/static" />
          </webroots>
          <sourceRoots>
            <root url="file:///work/demo/src/main/java" />
          </sourceRoots>
        </configuration>
    """.trimIndent()

    fun testReadsUltimateFormat() {
        val configuration = WebFacetConfiguration()
        configuration.loadState(JDOMUtil.load(ultimateXml))
        assertEquals(2, configuration.webRoots.size)
        assertEquals("/work/demo/src/main/webapp", configuration.webRoots[0].path)
        assertEquals("/static", configuration.webRoots[1].relativePath)
        assertEquals("file:///work/demo/src/main/webapp/WEB-INF/web.xml", configuration.webXmlUrl)
    }

    fun testRoundTripPreservesUnknownElements() {
        val configuration = WebFacetConfiguration()
        configuration.loadState(JDOMUtil.load(ultimateXml))
        val written = configuration.state
        val copy = WebFacetConfiguration()
        copy.loadState(written)
        assertEquals(configuration.webRoots, copy.webRoots)
        assertEquals(configuration.descriptors, copy.descriptors)
        assertNotNull("sourceRoots must be preserved", written.getChild("sourceRoots"))
    }
}
