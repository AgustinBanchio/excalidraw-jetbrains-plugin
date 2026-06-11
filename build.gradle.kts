import java.io.File

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform")
}

group = "com.agustinbanchio"
version = "0.2.0"

val platformVersion = providers.gradleProperty("platformVersion")
val platformSinceBuild = providers.gradleProperty("platformSinceBuild")
val platformUntilBuild = providers.gradleProperty("platformUntilBuild")
val npmExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"
val frontendDir = layout.projectDirectory.dir("frontend")
val generatedFrontendDir = layout.buildDirectory.dir("generated/resources/excalidraw-web")
val pluginSigningDir = providers.environmentVariable("PLUGIN_SIGNING_DIR")

dependencies {
    intellijPlatform {
        intellijIdea(platformVersion)
        bundledPlugin("com.intellij.java")
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
            untilBuild = platformUntilBuild
        }
    }

    signing {
        certificateChainFile = layout.file(pluginSigningDir.map { File(it, "chain.crt") })
        privateKeyFile = layout.file(pluginSigningDir.map { File(it, "private.pem") })
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
}

tasks {
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

    register<Copy>("copyFrontendResources") {
        group = "frontend"
        description = "Copies built frontend assets into generated plugin resources."
        dependsOn("buildFrontend")
        from(frontendDir.dir("dist"))
        into(generatedFrontendDir)
    }

    processResources {
        dependsOn("copyFrontendResources")
        from(generatedFrontendDir) {
            into("excalidraw-web")
        }
    }

    runIde {
        autoReload = false
    }
}
