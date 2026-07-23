package io.github.cdsap.td.paparazzi.plugin

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test

/**
 * Separated from the plugin class so that AGP classes are only loaded
 * when the Android plugin is actually present on the classpath.
 */
internal object AndroidVariantConfigurator {

    fun configure(project: Project, extension: TDPaparazziExtension) {
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }

            val testTaskName = "test${variantName}UnitTest"

            val inputReportDirPath = extension.inputReportDir.getOrElse(DEFAULT_INPUT_REPORT_DIR)
            val inputReportDir = project.layout.projectDirectory.dir(inputReportDirPath)

            val mergeTask = project.tasks.register(
                "mergePaparazzi${variantName}Outputs",
                MergePaparazziOutputsTask::class.java
            ) { task ->
                task.dependsOn(testTaskName)
                task.inputDirectory.set(inputReportDir)
                task.outputDirectory.set(
                    project.layout.projectDirectory.dir(
                        extension.outputReportDir.getOrElse("build/reports/paparazzi-td")
                    )
                )
                task.cleanupTdDirectories.set(extension.cleanupTdDirectories.orElse(false))
            }

            project.tasks.withType(Test::class.java).configureEach { testTask ->
                if (testTask.name == testTaskName) {
                    wireTestTask(testTask, mergeTask, inputReportDirPath)
                    registerPaparazziInputs(project, testTask, variant.name)
                }
            }
        }
    }

    /**
     * Wires a unit-test task to the TD report layout: sets the system property the writer
     * reads, and makes the merge task run after the test task finishes.
     *
     * [inputReportDirPath] should be a project-relative path (the same string the user
     * configures on the extension). Test Distribution does not path-remap system property
     * values, so a daemon-side absolute path would not resolve on remote agents — the
     * relative form lets each agent resolve it against its own workspace.
     */
    internal fun wireTestTask(
        testTask: Test,
        mergeTask: TaskProvider<MergePaparazziOutputsTask>,
        inputReportDirPath: String,
    ) {
        testTask.systemProperty(TD_REPORT_DIR_SYSTEM_PROPERTY, inputReportDirPath)
        testTask.finalizedBy(mergeTask)
    }

    /**
     * Registers files Paparazzi reads at runtime as named inputs of the unit-test task so
     * Test Distribution transfers them to remote agents. Paparazzi wires them only as task
     * dependencies or system-property paths, which TD does not transfer:
     *
     *  1. `intermediates/paparazzi/<variant>` — resources metadata written by
     *     `preparePaparazziResources`, wired only as a task dependency.
     *  2. `layoutlibResources` — the layoutlib runtime, passed only as a systemProperty path.
     *  3. Exploded resource dirs of external AAR dependencies — needed e.g. for vector
     *     drawables to render on agents.
     *
     * [variantName] must be the raw camelCase variant name (e.g. `freeDebug`) so both
     * `intermediates/paparazzi/<variant>` and `<variant>RuntimeClasspath` resolve for
     * flavored variants.
     */
    internal fun registerPaparazziInputs(
        project: Project,
        testTask: Test,
        variantName: String,
    ) {
        testTask.inputs.dir(
            project.layout.buildDirectory.dir("intermediates/paparazzi/$variantName")
        )
            .withPropertyName(PAPARAZZI_INTERMEDIATES_INPUT_PROPERTY)
            .withPathSensitivity(PathSensitivity.RELATIVE)

        project.configurations.findByName(LAYOUTLIB_RESOURCES_CONFIGURATION)?.let { configuration ->
            val layoutlibDirs = configuration.incoming.artifactView { view ->
                view.attributes.attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    ArtifactTypeDefinition.DIRECTORY_TYPE
                )
            }.files
            testTask.inputs.files(layoutlibDirs)
                .withPropertyName(PAPARAZZI_LAYOUTLIB_INPUT_PROPERTY)
                .withPathSensitivity(PathSensitivity.NONE)
        }

        project.configurations.findByName("${variantName}RuntimeClasspath")?.let { configuration ->
            val aarResDirs = configuration.incoming.artifactView { view ->
                view.attributes.attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    ANDROID_RES_ARTIFACT_TYPE
                )
                view.lenient(true)
                view.componentFilter { id -> id !is ProjectComponentIdentifier }
            }.files
            testTask.inputs.files(aarResDirs)
                .withPropertyName(PAPARAZZI_AAR_RES_INPUT_PROPERTY)
                .withPathSensitivity(PathSensitivity.NONE)
        }
    }

    internal const val TD_REPORT_DIR_SYSTEM_PROPERTY = "paparazzi.td.report.dir"
    internal const val DEFAULT_INPUT_REPORT_DIR = "build/reports/paparazzi"
    internal const val PAPARAZZI_INTERMEDIATES_INPUT_PROPERTY = "paparazzi.td.intermediates"
    internal const val PAPARAZZI_LAYOUTLIB_INPUT_PROPERTY = "paparazzi.layoutlib.resources"
    internal const val PAPARAZZI_AAR_RES_INPUT_PROPERTY = "paparazzi.aar.resource.dirs"
    internal const val LAYOUTLIB_RESOURCES_CONFIGURATION = "layoutlibResources"
    internal const val ANDROID_RES_ARTIFACT_TYPE = "android-res"
}
