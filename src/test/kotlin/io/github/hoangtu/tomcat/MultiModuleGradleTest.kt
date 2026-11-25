package io.github.hoangtu.tomcat

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.testFramework.JavaProjectTestCase
import com.intellij.testFramework.PsiTestUtil
import io.github.hoangtu.tomcat.web.WebFacet
import io.github.hoangtu.tomcat.web.WebFacetType
import io.github.hoangtu.tomcat.web.WebModulesSynchronizer
import io.github.hoangtu.tomcat.web.WebRoot

/**
 * Gradle multi-project as imported by IntelliJ: modules "app" (project dir) and "app.web.main" (content root web/src/main).
 * The "web" subproject has no build file of its own: the war plugin comes from the root build file.
 */
class MultiModuleGradleTest : JavaProjectTestCase() {
    private fun write(dir: VirtualFile, path: String, text: String) {
        val parent = VfsUtil.createDirectories(dir.path + "/" + path.substringBeforeLast('/', ""))
        VfsUtil.saveText(parent.findChild(path.substringAfterLast('/')) ?: parent.createChildData(this, path.substringAfterLast('/')), text)
    }

    fun testSubprojectWithoutBuildFileIsDetected() {
        val dir = createTempDirectory()
        val root = WriteAction.computeAndWait<VirtualFile, Exception> {
            val r = VfsUtil.findFileByIoFile(dir, true)!!
            write(r, "settings.gradle", "include 'web', 'lib'")
            write(r, "build.gradle", "subprojects { apply plugin: 'java' }\nproject(':web') { apply plugin: 'war' }")
            write(r, "web/src/main/java/web/Page.java", "package web; public class Page {}")
            write(r, "web/src/main/webapp/index.jsp", "web")
            write(r, "lib/src/main/java/lib/Lib.java", "package lib; public class Lib {}")
            r
        }
        PsiTestUtil.addContentRoot(module, root) // root project module
        val webMain = createModule("app.web.main")
        PsiTestUtil.addContentRoot(webMain, root.findFileByRelativePath("web/src/main")!!)
        PsiTestUtil.addSourceRoot(webMain, root.findFileByRelativePath("web/src/main/java")!!)
        val libMain = createModule("app.lib.main")
        PsiTestUtil.addContentRoot(libMain, root.findFileByRelativePath("lib/src/main")!!)
        PsiTestUtil.addSourceRoot(libMain, root.findFileByRelativePath("lib/src/main/java")!!)

        WebModulesSynchronizer.getInstance(project).syncNow()

        assertEquals(listOf(root.path + "/web/src/main/webapp"), WebFacet.getInstances(webMain).single().webRoots.map { it.path })
        assertEmpty(WebFacet.getInstances(libMain))
        assertEmpty(WebFacet.getInstances(module))
        val exploded = ArtifactManager.getInstance(project).findArtifact("app.web:war exploded")!!
        assertEquals(root.path + "/web/build/libs/exploded/web.war", exploded.outputPath)
    }

    /** A facet pointing at another module's web directory must not produce a WAR mixing both modules. */
    fun testFacetUsingAnotherModulesWebContentIsSkipped() {
        val dir = createTempDirectory()
        val root = WriteAction.computeAndWait<VirtualFile, Exception> {
            val r = VfsUtil.findFileByIoFile(dir, true)!!
            write(r, "pom.xml", "<project><packaging>pom</packaging></project>")
            write(r, "child/pom.xml", "<project><packaging>war</packaging></project>")
            write(r, "child/src/main/webapp/index.jsp", "child")
            r
        }
        PsiTestUtil.addContentRoot(module, root)
        val child = createModule("child")
        PsiTestUtil.addContentRoot(child, root.findChild("child")!!)
        // the parent module gets a (wrong) facet pointing into the child module, as could be added by hand
        WriteAction.runAndWait<Exception> {
            val type = WebFacetType.instance
            val config = type.createDefaultConfiguration().apply { webRoots = listOf(WebRoot(root.url + "/child/src/main/webapp")) }
            val model = FacetManager.getInstance(module).createModifiableModel()
            model.addFacet(FacetManager.getInstance(module).createFacet(type, "Web", config, null))
            model.commit()
        }

        WebModulesSynchronizer.getInstance(project).syncNow()

        val names = ArtifactManager.getInstance(project).artifacts.map { it.name }.toSet()
        assertEquals(setOf("child:war exploded", "child:war"), names)
    }
}
