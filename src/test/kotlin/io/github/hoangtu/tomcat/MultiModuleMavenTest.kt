package io.github.hoangtu.tomcat

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.packaging.impl.compiler.ArtifactCompileScope
import com.intellij.testFramework.CompilerTester
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.JavaProjectTestCase
import com.intellij.testFramework.PsiTestUtil
import io.github.hoangtu.tomcat.web.WebFacet
import io.github.hoangtu.tomcat.web.WebModulesSynchronizer
import java.io.File

/**
 * Maven multi-module project laid out like after a Maven import:
 * ```
 * parent/            pom (packaging pom)       module "parent", content root = parent/ (contains the others)
 *   common/          jar                       module "common"
 *   shop/            war, src/main/webapp      module "shop"  -> depends on common
 *   admin/           war, WebContent           module "admin" -> depends on common (maven-war-plugin warSourceDirectory)
 * ```
 */
class MultiModuleMavenTest : JavaProjectTestCase() {

    private fun pom(artifactId: String, packaging: String, extra: String = "") = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>demo</groupId><artifactId>$artifactId</artifactId><version>1.0</version>
          <packaging>$packaging</packaging>
          $extra
        </project>
    """.trimIndent()

    private fun write(dir: VirtualFile, path: String, text: String) {
        val parent = VfsUtil.createDirectories(dir.path + "/" + path.substringBeforeLast('/', ""))
        VfsUtil.saveText(parent.findChild(path.substringAfterLast('/')) ?: parent.createChildData(this, path.substringAfterLast('/')), text)
    }

    private fun javaModule(name: String, dir: VirtualFile): Module {
        val m = if (name == module.name) module else createModule(name)
        PsiTestUtil.addContentRoot(m, dir)
        dir.findFileByRelativePath("src/main/java")?.let { PsiTestUtil.addSourceRoot(m, it) }
        PsiTestUtil.setCompilerOutputPath(m, dir.path + "/target/classes", false)
        ModuleRootModificationUtil.setModuleSdk(m, sdk)
        IdeaTestUtil.setModuleLanguageLevel(m, com.intellij.pom.java.LanguageLevel.JDK_11)
        return m
    }

    private lateinit var sdk: com.intellij.openapi.projectRoots.Sdk

    fun testEachWarModuleGetsOnlyItsOwnWebContent() {
        sdk = JavaSdk.getInstance().createJdk("mm-jdk", System.getProperty("java.home"), false)
        WriteAction.runAndWait<Exception> { ProjectJdkTable.getInstance().addJdk(sdk, testRootDisposable) }

        val rootDir = createTempDirectory()
        val root = WriteAction.computeAndWait<VirtualFile, Exception> {
            val r = VfsUtil.findFileByIoFile(rootDir, true)!!
            write(r, "pom.xml", pom("parent", "pom", "<modules><module>common</module><module>shop</module><module>admin</module></modules>"))
            write(r, "common/pom.xml", pom("common", "jar"))
            write(r, "common/src/main/java/common/Util.java", "package common; public class Util {}")
            write(r, "shop/pom.xml", pom("shop", "war"))
            write(r, "shop/src/main/java/shop/ShopServlet.java", "package shop; public class ShopServlet {}")
            write(r, "shop/src/main/webapp/index.jsp", "shop page")
            write(r, "shop/src/main/webapp/WEB-INF/web.xml", "<web-app/>")
            write(r, "admin/pom.xml", pom("admin", "war", """
                <build><plugins><plugin>
                  <groupId>org.apache.maven.plugins</groupId><artifactId>maven-war-plugin</artifactId>
                  <configuration><warSourceDirectory>${'$'}{project.basedir}/WebContent</warSourceDirectory></configuration>
                </plugin></plugins></build>"""))
            write(r, "admin/src/main/java/admin/AdminServlet.java", "package admin; public class AdminServlet {}")
            write(r, "admin/WebContent/admin.jsp", "admin page")
            write(r, "admin/WebContent/WEB-INF/web.xml", "<web-app/>")
            r
        }
        val parent = javaModule(module.name, root)
        val common = javaModule("common", root.findChild("common")!!)
        val shop = javaModule("shop", root.findChild("shop")!!)
        val admin = javaModule("admin", root.findChild("admin")!!)
        ModuleRootModificationUtil.addDependency(shop, common)
        ModuleRootModificationUtil.addDependency(admin, common)

        WebModulesSynchronizer.getInstance(project).syncNow()

        // facets: only the two war modules, each with its own web directory (admin: from warSourceDirectory)
        assertEmpty(WebFacet.getInstances(parent))
        assertEmpty(WebFacet.getInstances(common))
        assertEquals(listOf(root.path + "/shop/src/main/webapp"), WebFacet.getInstances(shop).single().webRoots.map { it.path })
        assertEquals(listOf(root.path + "/admin/WebContent"), WebFacet.getInstances(admin).single().webRoots.map { it.path })
        assertTrue(WebFacet.getInstances(admin).single().configuration.webXmlUrl!!.endsWith("admin/WebContent/WEB-INF/web.xml"))

        val manager = ArtifactManager.getInstance(project)
        assertEquals(setOf("shop:war exploded", "shop:war", "admin:war exploded", "admin:war"), manager.artifacts.map { it.name }.toSet())
        val shopOut = manager.findArtifact("shop:war exploded")!!.outputPath!!
        val adminOut = manager.findArtifact("admin:war exploded")!!.outputPath!!
        assertEquals(root.path + "/shop/target/shop", shopOut)
        assertEquals(root.path + "/admin/target/admin", adminOut)

        // the tester is disposed together with the test (testRootDisposable)
        val tester = CompilerTester(project, listOf(parent, common, shop, admin), testRootDisposable)
        val messages = tester.make(ArtifactCompileScope.createArtifactsScope(project, manager.artifacts.toList()))
        assertTrue(messages.filter { it.category == CompilerMessageCategory.ERROR }.joinToString { it.message },
            messages.none { it.category == CompilerMessageCategory.ERROR })

        fun files(dir: String) = File(dir).walkTopDown().filter { it.isFile }.map { it.relativeTo(File(dir)).invariantSeparatorsPath }.toSortedSet()
        assertEquals(sortedSetOf("index.jsp", "WEB-INF/web.xml", "WEB-INF/classes/shop/ShopServlet.class", "WEB-INF/lib/common.jar"), files(shopOut))
        assertEquals(sortedSetOf("admin.jsp", "WEB-INF/web.xml", "WEB-INF/classes/admin/AdminServlet.class", "WEB-INF/lib/common.jar"), files(adminOut))

        // the build file changes -> the facet created by the plugin follows it
        WriteAction.runAndWait<Exception> {
            write(root, "admin/pom.xml", pom("admin", "war", """
                <build><plugins><plugin><artifactId>maven-war-plugin</artifactId>
                  <configuration><warSourceDirectory>web</warSourceDirectory></configuration>
                </plugin></plugins></build>"""))
        }
        WebModulesSynchronizer.getInstance(project).syncNow()
        assertEquals(listOf(root.path + "/admin/web"), WebFacet.getInstances(admin).single().webRoots.map { it.path })
    }
}
