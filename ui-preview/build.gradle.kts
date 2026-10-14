plugins { id("com.android.application") }
android {
    namespace = "app.yydarlinker.extension"
    compileSdk = 35
    defaultConfig { applicationId = "app.yydarlinker.n39preview"; minSdk = 28; targetSdk = 35; versionCode = 1; versionName = "reintegrated-ui-preview" }
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField("String", "CAPTION_PATCH_VERSION", "\"${rootProject.version}\"")
        buildConfigField("String", "CAPTION_BUILD_ID", "\"${providers.gradleProperty("captionBuildId").get()}-ui-fixture\"")
    }
    sourceSets.getByName("main").java.srcDir(rootProject.file("work/ui-preview-sources"))
    sourceSets.getByName("main").res.srcDir(rootProject.file("patches/src/main/resources/captionlocales"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies { implementation(project(":caption-contracts")); implementation(project(":caption-shared")) }
val stageUi = tasks.register<Sync>("stageIndependentUi") {
    into(rootProject.file("work/ui-preview-sources"))
    from(rootProject.file("ui/src/main/java"))
    from(rootProject.file("presentation/src/main/java"))
    from(rootProject.file("infrastructure/src/main/java")) {
        include("**/ApiProfiles.java", "**/DeepSeekConfig.java", "**/SecureApiKey.java", "**/TokenCostAudit.java", "**/TokenCostReport.java",
            "**/CaptionDiagnostic*.java", "**/CaptionQualityTrace.java", "**/CaptionSourceIdentity.java", "**/SourceFormatPolicy.java", "**/SourceCaptionCache.java")
    }
    from(rootProject.file("application-host/src/main/java")) {
        exclude("**/CaptionApplication.java", "**/OfficialPlayerClockAdapter.java", "**/TranslationSourceSelector.java", "**/DeepSeekCaptionHook.java", "**/DeepSeekCaptionHookV2.java")
    }
}
tasks.named("preBuild") { dependsOn(stageUi) }
