package io.github.hoangtu.tomcat

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.packaging.impl.compiler.ArtifactCompileScope
import com.intellij.testFramework.CompilerTester
import com.intellij.testFramework.JavaProjectTestCase
import com.intellij.testFramework.PsiTestUtil
import io.github.hoangtu.tomcat.web.WebModulesSynchronizer
import java.io.File

/** Editing one Java file recompiles only that class and copies only it into the exploded WAR. */
class IncrementalBuildTest : JavaProjectTestCase() {
    private var tester: CompilerTester? = null

    override fun tearDown() {
        try { tester?.tearDown() } finally { super.tearDown() }
    }

    fun testOnlyChangedClassIsRebuilt() {
        val sdk = JavaSdk.getInstance().createJdk("incremental-jdk", System.getProperty("java.home"), false)
        WriteAction.runAndWait<Exception> { ProjectJdkTable.getInstance().addJdk(sdk, testRootDisposable) }
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
        com.intellij.testFramework.IdeaTestUtil.setModuleLanguageLevel(module, com.intellij.pom.java.LanguageLevel.JDK_11)

        val dir = createTempDirectory()
        lateinit var a: VirtualFile
        val root = WriteAction.computeAndWait<VirtualFile, Exception> {
            val r = VfsUtil.findFileByIoFile(dir, true)!!
            VfsUtil.saveText(r.createChildData(this, "pom.xml"), "<project><packaging>war</packaging></project>")
            val pkg = VfsUtil.createDirectories(r.path + "/src/main/java/demo")
            a = pkg.createChildData(this, "A.java")
            VfsUtil.saveText(a, "package demo; public class A { public String hello() { return \"v1\"; } }")
            for (name in listOf("B", "C", "D")) {
                VfsUtil.saveText(pkg.createChildData(this, "$name.java"), "package demo; public class $name {}")
            }
            VfsUtil.saveText(VfsUtil.createDirectories(r.path + "/src/main/webapp").createChildData(this, "index.jsp"), "hi")
            r
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root.findFileByRelativePath("src/main/java")!!)
        PsiTestUtil.setCompilerOutputPath(module, root.path + "/out/classes", false)
        WebModulesSynchronizer.getInstance(project).syncNow() // creates "<module>:war exploded" like for a Maven web module

        val exploded = ArtifactManager.getInstance(project).findArtifact("${module.name}:war exploded")!!
        val scope = ArtifactCompileScope.createArtifactsScope(project, listOf(exploded))
        tester = CompilerTester(module)
        assertTrue(tester!!.make(scope).none { it.category == CompilerMessageCategory.ERROR })

        val classes = File(exploded.outputPath!!, "WEB-INF/classes/demo")
        fun stamps() = classes.listFiles()!!.associate { it.name to it.lastModified() }
        val before = stamps()
        assertEquals(setOf("A.class", "B.class", "C.class", "D.class"), before.keys)

        Thread.sleep(1100) // file systems with 1s timestamp resolution
        tester!!.setFileText(com.intellij.psi.PsiManager.getInstance(project).findFile(a)!!,
            "package demo; public class A { public String hello() { return \"v2\"; } }")
        assertTrue(tester!!.make(scope).none { it.category == CompilerMessageCategory.ERROR })
        val after = stamps()

        assertTrue("A.class must be recompiled and copied", after["A.class"]!! > before["A.class"]!!)
        for (name in listOf("B.class", "C.class", "D.class")) {
            assertEquals("$name must not be touched", before[name], after[name])
        }
    }
}
