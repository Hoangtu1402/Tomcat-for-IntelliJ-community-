package io.github.hoangtu.tomcat

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.packaging.elements.PackagingElementFactory
import com.intellij.packaging.impl.compiler.ArtifactCompileScope
import com.intellij.testFramework.CompilerTester
import com.intellij.testFramework.JavaProjectTestCase
import com.intellij.testFramework.PsiTestUtil
import io.github.hoangtu.tomcat.artifact.ExplodedWarArtifactType
import io.github.hoangtu.tomcat.artifact.WarArtifactType
import io.github.hoangtu.tomcat.artifact.WebArtifactLayout
import io.github.hoangtu.tomcat.artifact.WebFacetResourcesElement
import io.github.hoangtu.tomcat.web.WebFacet
import io.github.hoangtu.tomcat.web.WebFacetType
import io.github.hoangtu.tomcat.web.WebRoot
import java.io.File
import java.util.zip.ZipFile

/**
 * Builds web artifacts with the external build process (JPS), including the Ultimate-style
 * `javaee-facet-resources` layout element that is handled by the plugin's JPS extension.
 */
class ArtifactBuildTest : JavaProjectTestCase() {
    private var sdk: Sdk? = null
    private var tester: CompilerTester? = null

    override fun setUp() {
        super.setUp()
        sdk = JavaSdk.getInstance().createJdk("build-test-jdk", System.getProperty("java.home"), false)
        WriteAction.runAndWait<Exception> { ProjectJdkTable.getInstance().addJdk(sdk!!, testRootDisposable) }
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        com.intellij.testFramework.IdeaTestUtil.setModuleLanguageLevel(module, com.intellij.pom.java.LanguageLevel.JDK_17)
    }

    override fun tearDown() {
        try {
            tester?.tearDown()
        } finally {
            super.tearDown()
        }
    }

    fun testBuildsExplodedAndWarArtifactsWithFacetResources() {
        val dir = createTempDirectory()
        val root = WriteAction.computeAndWait<VirtualFile, Exception> {
            val r = VfsUtil.findFileByIoFile(dir, true)!!
            val src = VfsUtil.createDirectories(r.path + "/src/main/java/demo")
            VfsUtil.saveText(src.createChildData(this, "Hello.java"), "package demo; public class Hello {}")
            val webapp = VfsUtil.createDirectories(r.path + "/src/main/webapp/WEB-INF")
            VfsUtil.saveText(webapp.createChildData(this, "web.xml"), "<web-app/>")
            VfsUtil.saveText(webapp.parent.createChildData(this, "index.jsp"), "<html>hello</html>")
            r
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root.findFileByRelativePath("src/main/java")!!)
        PsiTestUtil.setCompilerOutputPath(module, root.path + "/out/classes", false)

        // Web facet + an artifact laid out exactly like IntelliJ IDEA Ultimate does it
        val facet = WriteAction.computeAndWait<WebFacet, Exception> {
            val type = WebFacetType.instance
            val configuration = type.createDefaultConfiguration().apply { webRoots = listOf(WebRoot(root.url + "/src/main/webapp", "/")) }
            val model = FacetManager.getInstance(module).createModifiableModel()
            val f = FacetManager.getInstance(module).createFacet(type, "Web", configuration, null)
            model.addFacet(f)
            model.commit()
            f
        }
        val explodedOut = root.path + "/out/exploded"
        WriteAction.runAndWait<Exception> {
            val factory = PackagingElementFactory.getInstance()
            val model = ArtifactManager.getInstance(project).createModifiableModel()
            val rootElement = factory.createArtifactRootElement()
            rootElement.addOrFindChild(WebFacetResourcesElement.forFacet(facet))
            factory.getOrCreateDirectory(rootElement, "WEB-INF/classes").addOrFindChild(factory.createModuleOutput(module))
            val exploded = model.addArtifact("demo:war exploded", ExplodedWarArtifactType.getInstance(), rootElement)
            exploded.outputPath = explodedOut
            val war = model.addArtifact("demo:war", WarArtifactType.getInstance(), WebArtifactLayout.createWarRoot(project, exploded, "demo:war"))
            war.outputPath = root.path + "/out/war"
            model.commit()
        }

        val artifacts = ArtifactManager.getInstance(project).artifacts.toList()
        tester = CompilerTester(module)
        val messages = tester!!.make(ArtifactCompileScope.createArtifactsScope(project, artifacts))
        val errors = messages.filter { it.category == CompilerMessageCategory.ERROR }
        assertTrue("Build errors: " + errors.joinToString { it.message }, errors.isEmpty())

        assertTrue("web resources copied by the JPS extension", File(explodedOut, "index.jsp").isFile)
        assertTrue(File(explodedOut, "WEB-INF/web.xml").isFile)
        assertTrue(File(explodedOut, "WEB-INF/classes/demo/Hello.class").isFile)

        val warFile = File(root.path + "/out/war/demo.war")
        assertTrue("war file must exist", warFile.isFile)
        ZipFile(warFile).use { zip ->
            assertNotNull(zip.getEntry("index.jsp"))
            assertNotNull(zip.getEntry("WEB-INF/classes/demo/Hello.class"))
        }
    }
}
