
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.gtnewhorizons.gtnhconvention")
}

version = "0.1.0"

dependencies {
    "shadowCompile"(project(":core"))
}

configurations.named("shadowCompile") {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

tasks.named<ShadowJar>("shadowJar") {
    relocate("kotlin", "me.sorihiko.dangomusicgtnh.shadow.kotlin")
    relocate("org.jetbrains.annotations", "me.sorihiko.dangomusicgtnh.shadow.annotations")
    relocate("javazoom", "me.sorihiko.dangomusicgtnh.shadow.javazoom")
    relocate("com.jcraft", "me.sorihiko.dangomusicgtnh.shadow.jcraft")
    relocate("com.google.zxing", "me.sorihiko.dangomusicgtnh.shadow.zxing")
    exclude("module-info.class", "META-INF/versions/**", "META-INF/*.kotlin_module")
}
