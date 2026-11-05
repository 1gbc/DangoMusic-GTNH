plugins {
    `java-library`
    kotlin("jvm")
}

repositories {
    mavenCentral()
    maven("https://libraries.minecraft.net")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

kotlin {
    jvmToolchain(8)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly("com.google.code.gson:gson:2.2.4")
    compileOnly("org.apache.logging.log4j:log4j-api:2.0-beta9")
    implementation("org.jcraft:jorbis:0.0.17")
    implementation("javazoom:jlayer:1.0.1")
    implementation("com.google.zxing:core:3.3.3")
}
