package io.github.cdsap.td.paparazzi.plugin

import org.gradle.api.internal.TaskInputsInternal
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.execution.model.InputNormalizer
import org.gradle.internal.fingerprint.DirectorySensitivity
import org.gradle.internal.fingerprint.FileNormalizer
import org.gradle.internal.fingerprint.LineEndingSensitivity
import org.gradle.internal.properties.InputBehavior
import org.gradle.internal.properties.InputFilePropertyType
import org.gradle.internal.properties.PropertyValue
import org.gradle.internal.properties.PropertyVisitor
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test as JTest

class AndroidVariantConfiguratorTest {

    @JTest
    fun `wireTestTask sets td report dir system property to the relative path`() {
        val project = ProjectBuilder.builder().build()
        val testTask = project.tasks.create("testDebugUnitTest", Test::class.java)
        val mergeTask = project.tasks.register(
            "mergePaparazziDebugOutputs",
            MergePaparazziOutputsTask::class.java
        )

        AndroidVariantConfigurator.wireTestTask(testTask, mergeTask, "build/reports/paparazzi")

        // Must be the relative path, not absolutized. Test Distribution does not remap
        // system-property values, so daemon-side absolute paths would not resolve on agents.
        assertEquals(
            "build/reports/paparazzi",
            testTask.systemProperties[AndroidVariantConfigurator.TD_REPORT_DIR_SYSTEM_PROPERTY]
        )
    }

    @JTest
    fun `wireTestTask makes merge task a finalizer of the test task`() {
        val project = ProjectBuilder.builder().build()
        val testTask = project.tasks.create("testDebugUnitTest", Test::class.java)
        val mergeTask = project.tasks.register(
            "mergePaparazziDebugOutputs",
            MergePaparazziOutputsTask::class.java
        )

        AndroidVariantConfigurator.wireTestTask(testTask, mergeTask, "build/reports/paparazzi")

        val finalizers = testTask.finalizedBy.getDependencies(testTask).map { it.name }
        assertTrue(
            finalizers.contains("mergePaparazziDebugOutputs"),
            "Expected mergePaparazziDebugOutputs to be a finalizer; got: $finalizers"
        )
    }

    @JTest
    fun `td report dir system property name is paparazzi td report dir`() {
        // Locks the property name — TDHtmlReportWriter reads the same string,
        // so renaming on either side without updating the other would silently break the wiring.
        assertEquals("paparazzi.td.report.dir", AndroidVariantConfigurator.TD_REPORT_DIR_SYSTEM_PROPERTY)
    }

    @JTest
    fun `registerPaparazziInputs registers intermediates dir with relative path sensitivity`() {
        val project = ProjectBuilder.builder().build()
        val testTask = project.tasks.create("testDebugUnitTest", Test::class.java)

        AndroidVariantConfigurator.registerPaparazziInputs(project, testTask, "debug")

        val inputs = registeredInputs(testTask)
        val intermediates = inputs.single {
            it.name == AndroidVariantConfigurator.PAPARAZZI_INTERMEDIATES_INPUT_PROPERTY
        }
        assertEquals(InputNormalizer.RELATIVE_PATH, intermediates.normalizer)
        // inputs.dir registers a file tree, so resolve through a file inside the directory.
        val resourcesFile = project.layout.buildDirectory
            .file("intermediates/paparazzi/debug/resources.json").get().asFile
        resourcesFile.parentFile.mkdirs()
        resourcesFile.writeText("{}")
        assertTrue(
            testTask.inputs.files.files.contains(resourcesFile),
            "Expected the paparazzi intermediates contents among the test task input files"
        )
    }

    @JTest
    fun `registerPaparazziInputs registers layoutlib resources when configuration exists`() {
        val project = ProjectBuilder.builder().build()
        project.configurations.create("layoutlibResources")
        val testTask = project.tasks.create("testDebugUnitTest", Test::class.java)

        AndroidVariantConfigurator.registerPaparazziInputs(project, testTask, "debug")

        val inputs = registeredInputs(testTask)
        val layoutlib = inputs.single {
            it.name == AndroidVariantConfigurator.PAPARAZZI_LAYOUTLIB_INPUT_PROPERTY
        }
        assertEquals(InputNormalizer.IGNORE_PATH, layoutlib.normalizer)
    }

    @JTest
    fun `registerPaparazziInputs registers aar resource dirs when runtime classpath exists`() {
        val project = ProjectBuilder.builder().build()
        project.configurations.create("debugRuntimeClasspath")
        val testTask = project.tasks.create("testDebugUnitTest", Test::class.java)

        AndroidVariantConfigurator.registerPaparazziInputs(project, testTask, "debug")

        val inputs = registeredInputs(testTask)
        val aarRes = inputs.single {
            it.name == AndroidVariantConfigurator.PAPARAZZI_AAR_RES_INPUT_PROPERTY
        }
        assertEquals(InputNormalizer.IGNORE_PATH, aarRes.normalizer)
    }

    @JTest
    fun `registerPaparazziInputs skips configuration-backed inputs when configurations are absent`() {
        val project = ProjectBuilder.builder().build()
        val testTask = project.tasks.create("testDebugUnitTest", Test::class.java)

        AndroidVariantConfigurator.registerPaparazziInputs(project, testTask, "debug")

        val names = registeredInputs(testTask).map { it.name }
        assertTrue(names.contains(AndroidVariantConfigurator.PAPARAZZI_INTERMEDIATES_INPUT_PROPERTY))
        assertFalse(names.contains(AndroidVariantConfigurator.PAPARAZZI_LAYOUTLIB_INPUT_PROPERTY))
        assertFalse(names.contains(AndroidVariantConfigurator.PAPARAZZI_AAR_RES_INPUT_PROPERTY))
    }

    @JTest
    fun `registerPaparazziInputs preserves camelCase variant names for flavored variants`() {
        val project = ProjectBuilder.builder().build()
        // Only the camelCase configuration exists; a lowercased lookup
        // (freedebugRuntimeClasspath) would find nothing.
        project.configurations.create("freeDebugRuntimeClasspath")
        val testTask = project.tasks.create("testFreeDebugUnitTest", Test::class.java)

        AndroidVariantConfigurator.registerPaparazziInputs(project, testTask, "freeDebug")

        val names = registeredInputs(testTask).map { it.name }
        assertTrue(names.contains(AndroidVariantConfigurator.PAPARAZZI_AAR_RES_INPUT_PROPERTY))
        // inputs.dir registers a file tree, so resolve through a file inside the directory.
        // Only the camelCase path is populated; a lowercased registration would miss it.
        val resourcesFile = project.layout.buildDirectory
            .file("intermediates/paparazzi/freeDebug/resources.json").get().asFile
        resourcesFile.parentFile.mkdirs()
        resourcesFile.writeText("{}")
        assertTrue(
            testTask.inputs.files.files.contains(resourcesFile),
            "Expected the camelCase freeDebug intermediates contents among the test task input files"
        )
    }

    @JTest
    fun `td input property names are stable`() {
        // Locks the property names — Develocity/Test Distribution configuration and docs
        // reference them, and consumers migrating off the manual workaround rely on them.
        assertEquals("paparazzi.td.intermediates", AndroidVariantConfigurator.PAPARAZZI_INTERMEDIATES_INPUT_PROPERTY)
        assertEquals("paparazzi.layoutlib.resources", AndroidVariantConfigurator.PAPARAZZI_LAYOUTLIB_INPUT_PROPERTY)
        assertEquals("paparazzi.aar.resource.dirs", AndroidVariantConfigurator.PAPARAZZI_AAR_RES_INPUT_PROPERTY)
    }

    private data class RegisteredInput(val name: String, val normalizer: FileNormalizer?)

    /**
     * Captures the input file properties registered through the runtime API
     * (task.inputs.dir/files). Uses Gradle-internal API pinned to the wrapper version
     * (9.2.1); if a wrapper upgrade breaks it, fall back to name-only assertions via
     * testTask.inputs.files.
     */
    private fun registeredInputs(testTask: Test): List<RegisteredInput> {
        val captured = mutableListOf<RegisteredInput>()
        val inputs: TaskInputsInternal = testTask.inputs
        inputs.visitRegisteredProperties(object : PropertyVisitor {
            override fun visitInputFileProperty(
                propertyName: String,
                optional: Boolean,
                behavior: InputBehavior,
                directorySensitivity: DirectorySensitivity,
                lineEndingSensitivity: LineEndingSensitivity,
                fileNormalizer: FileNormalizer?,
                value: PropertyValue,
                filePropertyType: InputFilePropertyType,
            ) {
                captured += RegisteredInput(propertyName, fileNormalizer)
            }
        })
        return captured
    }
}
