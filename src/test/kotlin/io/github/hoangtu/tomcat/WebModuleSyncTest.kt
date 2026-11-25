package io.github.hoangtu.tomcat

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.packaging.elements.CompositePackagingElement
import com.intellij.packaging.elements.PackagingElement
import com.intellij.packaging.impl.elements.LibraryPackagingElement
import com.intellij.packaging.impl.elements.ProductionModuleOutputPackagingElement
import com.intellij.testFramework.JavaProjectTestCase
import com.intellij.testFramework.PsiTestUtil
import io.github.hoangtu.tomcat.artifact.ExplodedWarArtifactType
import io.github.hoangtu.tomcat.artifact.WarArtifactType
import io.github.hoangtu.tomcat.web.WebFacet
import io.github.hoangtu.tomcat.web.WebModulesSynchronizer

class WebModuleSyncTest : JavaProjectTestCase() {
    private lateinit var root: VirtualFile

    private fun createLibrary(name: String, jar: VirtualFile, scope: DependencyScope) {
        val library = WriteAction.computeAndWait<com.intellij.openapi.roots.libraries.Library, RuntimeException> {
            val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
            val lib = table.createLibrary(name)
            val model = lib.modifiableModel
            model.addRoot(jar, OrderRootType.CLASSES)
            model.commit()
            lib
        }
        ModuleRootModificationUtil.addDependency(module, library, scope, false)
    }

    override fun setUp() {
        super.setUp()
        val dir = createTempDirectory()
        root = WriteAction.computeAndWait<VirtualFile, Exception> {
            val r = VfsUtil.findFileByIoFile(dir, true)!!
            VfsUtil.saveText(r.createChildData(this, "pom.xml"), "<project><packaging>war</packaging></project>")
            VfsUtil.createDirectories(r.path + "/src/main/java")
            val webInf = VfsUtil.createDirectories(r.path + "/src/main/webapp/WEB-INF")
            VfsUtil.saveText(webInf.createChildData(this, "web.xml"), "<web-app/>")
            VfsUtil.createDirectories(r.path + "/libs").let {
                it.createChildData(this, "runtime.jar")
                it.createChildData(this, "provided.jar")
            }
            r
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root.findFileByRelativePath("src/main/java")!!)
        createLibrary("Maven: demo:runtime:1.0", root.findFileByRelativePath("libs/runtime.jar")!!, DependencyScope.COMPILE)
        createLibrary("Maven: jakarta.servlet:api:6.0", root.findFileByRelativePath("libs/provided.jar")!!, DependencyScope.PROVIDED)
    }

    private fun collect(element: PackagingElement<*>, path: String, out: MutableMap<String, MutableList<PackagingElement<*>>>) {
        out.getOrPut(path) { mutableListOf() } += element
        if (element is CompositePackagingElement<*>) {
            for (child in element.children) {
                val childPath = if (child is CompositePackagingElement<*>) "$path/${child.name}" else path
                collect(child, childPath, out)
            }
        }
    }

    fun testWebModuleGetsFacetAndArtifacts() {
        WebModulesSynchronizer.getInstance(project).syncNow()

        val facet = FacetManager.getInstance(module).getFacetsByType(WebFacet.ID).single()
        assertEquals(root.path + "/src/main/webapp", facet.webRoots.single().path)
        assertTrue(facet.configuration.webXmlUrl!!.endsWith("WEB-INF/web.xml"))

        val manager = ArtifactManager.getInstance(project)
        val exploded = manager.findArtifact("${module.name}:war exploded")!!
        assertEquals(ExplodedWarArtifactType.ID, exploded.artifactType.id)
        assertEquals("${root.path}/target/${module.name}", exploded.outputPath)
        val war = manager.findArtifact("${module.name}:war")!!
        assertEquals(WarArtifactType.ID, war.artifactType.id)
        assertEquals("${root.path}/target", war.outputPath)

        val byPath = LinkedHashMap<String, MutableList<PackagingElement<*>>>()
        collect(exploded.rootElement, "", byPath)
        assertTrue(byPath["/WEB-INF/classes"]!!.any { it is ProductionModuleOutputPackagingElement })
        val libs = byPath["/WEB-INF/lib"]!!.filterIsInstance<LibraryPackagingElement>().map { it.libraryName }
        assertEquals(listOf("Maven: demo:runtime:1.0"), libs) // provided dependency is not packaged

        // nothing changes on a second run
        WebModulesSynchronizer.getInstance(project).syncNow()
        assertEquals(2, manager.artifacts.size)
    }

    fun testManagedArtifactFollowsDependencies() {
        WebModulesSynchronizer.getInstance(project).syncNow()
        val jar = WriteAction.computeAndWait<VirtualFile, Exception> { root.findChild("libs")!!.createChildData(this, "extra.jar") }
        createLibrary("Maven: demo:extra:2.0", jar, DependencyScope.RUNTIME)
        WebModulesSynchronizer.getInstance(project).syncNow()

        val exploded = ArtifactManager.getInstance(project).findArtifact("${module.name}:war exploded")!!
        val byPath = LinkedHashMap<String, MutableList<PackagingElement<*>>>()
        collect(exploded.rootElement, "", byPath)
        val libs = byPath["/WEB-INF/lib"]!!.filterIsInstance<LibraryPackagingElement>().map { it.libraryName }.sorted()
        assertEquals(listOf("Maven: demo:extra:2.0", "Maven: demo:runtime:1.0"), libs)
    }
}
