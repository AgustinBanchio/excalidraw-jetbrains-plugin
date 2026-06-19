import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction

abstract class VerifySyncedDirectories : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val targetDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        fun relativeFiles(root: File): Set<String> = root.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()

        val sourceFiles = relativeFiles(sourceDirectory.get().asFile)
        val targetFiles = relativeFiles(targetDirectory.get().asFile)
        check(sourceFiles == targetFiles) {
            val missing = sourceFiles - targetFiles
            val obsolete = targetFiles - sourceFiles
            "Generated frontend resources differ from Vite output. Missing: $missing; obsolete: $obsolete"
        }
    }
}

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform")
}

group = "com.agustinbanchio"
version = "0.2.5"

val platformVersion = providers.gradleProperty("platformVersion")
val platformProduct = providers.gradleProperty("platformProduct").orElse("idea")
val platformSinceBuild = providers.gradleProperty("platformSinceBuild")
val npmExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"
val frontendDir = layout.projectDirectory.dir("frontend")
val generatedFrontendDir = layout.buildDirectory.dir("generated/resources/excalidraw-web")
val pluginSigningDir = providers.environmentVariable("PLUGIN_SIGNING_DIR")

dependencies {
    testImplementation(kotlin("test"))

    intellijPlatform {
        when (platformProduct.get()) {
            "goland" -> goland(platformVersion)
            "idea" -> intellijIdea(platformVersion)
            else -> error("Unsupported platformProduct: ${platformProduct.get()}")
        }
        pluginVerifier()
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = platformSinceBuild
        }
    }

    signing {
        certificateChainFile = layout.file(pluginSigningDir.map { File(it, "chain.crt") })
        privateKeyFile = layout.file(pluginSigningDir.map { File(it, "private.pem") })
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    register<Exec>("npmInstallFrontend") {
        group = "frontend"
        description = "Installs frontend dependencies."
        workingDir = frontendDir.asFile
        commandLine(npmExecutable, "ci", "--no-audit", "--no-fund", "--loglevel=error")
        inputs.file(frontendDir.file("package.json"))
        inputs.file(frontendDir.file("package-lock.json"))
        outputs.dir(frontendDir.dir("node_modules"))
    }

    register<Exec>("buildFrontend") {
        group = "frontend"
        description = "Builds the Vite frontend for bundling into the plugin."
        dependsOn("npmInstallFrontend")
        workingDir = frontendDir.asFile
        commandLine(npmExecutable, "run", "build")
        inputs.dir(frontendDir.dir("src"))
        inputs.file(frontendDir.file("index.html"))
        inputs.file(frontendDir.file("package.json"))
        inputs.file(frontendDir.file("package-lock.json"))
        inputs.file(frontendDir.file("tsconfig.json"))
        inputs.file(frontendDir.file("vite.config.ts"))
        outputs.dir(frontendDir.dir("dist"))
    }

    register<Exec>("testFrontend") {
        group = "verification"
        description = "Runs the frontend unit tests."
        dependsOn("npmInstallFrontend")
        workingDir = frontendDir.asFile
        commandLine(npmExecutable, "test")
        inputs.dir(frontendDir.dir("src"))
        inputs.file(frontendDir.file("package.json"))
        inputs.file(frontendDir.file("package-lock.json"))
    }

    register<Exec>("checkThirdPartyNotices") {
        group = "verification"
        description = "Checks that bundled frontend dependency notices are current."
        dependsOn("npmInstallFrontend")
        workingDir = frontendDir.asFile
        commandLine(npmExecutable, "run", "licenses:check")
        inputs.file(frontendDir.file("scripts/generate-third-party-notices.mjs"))
        inputs.file(frontendDir.file("package-lock.json"))
        inputs.file(layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
    }

    val copyFrontendResources = register<Sync>("copyFrontendResources") {
        group = "frontend"
        description = "Synchronizes built frontend assets into generated plugin resources."
        dependsOn("buildFrontend")
        from(frontendDir.dir("dist"))
        into(generatedFrontendDir)
        doNotTrackState("The destination must be checked for obsolete Vite chunks on every packaging run.")
    }

    register<VerifySyncedDirectories>("verifyFrontendResources") {
        group = "verification"
        description = "Checks that generated plugin resources exactly match the Vite build."
        dependsOn(copyFrontendResources)
        sourceDirectory.set(frontendDir.dir("dist"))
        targetDirectory.set(generatedFrontendDir)
    }

    processResources {
        dependsOn("copyFrontendResources")
        from(generatedFrontendDir) {
            into("excalidraw-web")
        }
        from(layout.projectDirectory.file("LICENSE")) {
            into("META-INF")
        }
        from(layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) {
            into("META-INF")
        }
    }

    runIde {
        autoReload = false
    }

    verifyPluginSignature {
        dependsOn(signPlugin)
    }

    check {
        dependsOn("testFrontend", "checkThirdPartyNotices", "verifyFrontendResources")
    }
}
