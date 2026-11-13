
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.gtnewhorizons.gtnhconvention")
}

version = "0.2.0-dev.1"

dependencies {
    "shadowCompile"(project(":core"))
}

configurations.named("shadowCompile") {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

val applePlayerPublishDir = layout.buildDirectory.dir("apple-player/publish")
val applePlayerProject = "tools/apple-native-player/AppleNativePlayer.csproj"
val applePlayerFiles = listOf(
    "DangoApplePlayer.exe",
    "DangoApplePlayer.dll",
    "DangoApplePlayer.deps.json",
    "DangoApplePlayer.runtimeconfig.json",
    "Microsoft.Web.WebView2.Core.dll",
    "Microsoft.Web.WebView2.WinForms.dll",
    "WebView2Loader.dll",
    "runtimes/win-x64/native/WebView2Loader.dll"
)

val publishApplePlayer by tasks.registering(Exec::class) {
    inputs.files(fileTree("tools/apple-native-player") { include("*.cs", "*.csproj") })
    inputs.file("tools/apple-web-bridge/extension/adapter.js")
    outputs.dir(applePlayerPublishDir)
    commandLine(
        providers.gradleProperty("dotnetExecutable").getOrElse("dotnet"),
        "publish", applePlayerProject, "-c", "Release", "-r", "win-x64",
        "--self-contained", "false", "-p:EnableWindowsTargeting=true",
        "-o", applePlayerPublishDir.get().asFile.absolutePath
    )
}

val bundleApplePlayer by tasks.registering(Zip::class) {
    dependsOn(publishApplePlayer)
    archiveFileName.set("windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("apple-player/bundle"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(applePlayerPublishDir) { include(applePlayerFiles) }
    into("licenses") {
        from("LICENSE") { rename { "DangoMusic-GPL-3.0.txt" } }
        from("third-party/webview2") {
            include("LICENSE.txt", "NOTICE.txt")
            rename { "WebView2-SDK-$it" }
        }
    }
    doFirst {
        for (name in applePlayerFiles) {
            check(applePlayerPublishDir.get().file(name).asFile.isFile) { "Missing Apple player build output: $name" }
        }
    }
}

tasks.processResources {
    from(bundleApplePlayer) { into("assets/dangomusicgtnh/apple-player") }
}

tasks.named<ShadowJar>("shadowJar") {
    relocate("kotlin", "me.sorihiko.dangomusicgtnh.shadow.kotlin")
    relocate("org.jetbrains.annotations", "me.sorihiko.dangomusicgtnh.shadow.annotations")
    relocate("javazoom", "me.sorihiko.dangomusicgtnh.shadow.javazoom")
    relocate("com.jcraft", "me.sorihiko.dangomusicgtnh.shadow.jcraft")
    relocate("com.google.zxing", "me.sorihiko.dangomusicgtnh.shadow.zxing")
    exclude("module-info.class", "META-INF/versions/**", "META-INF/*.kotlin_module")
}
