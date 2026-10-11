allprojects {
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/n39/gradle/" + if (path == ":") "root" else path.trimStart(':').replace(':', '/')))
}

// Enforce the integration boundary on the actual Android delivery entrance.
val verifyIntegrationSources = tasks.register<Exec>("verifyIntegrationSources") {
    workingDir(rootProject.projectDir)
    val python = providers.environmentVariable("MORPHE_TASK_PYTHON").orElse("python")
    commandLine(python.get(), "-X", "utf8", "tools/verify_integration.py", "--source-only")
}
allprojects {
    tasks.configureEach {
        if (name == "assembleRelease" || project.path == ":patches" && name in setOf("build", "buildAndroid")) {
            dependsOn(verifyIntegrationSources)
        }
    }
}
