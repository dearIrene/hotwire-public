import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    checkstyle
    id("net.ltgt.errorprone")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
    options.errorprone.disableWarningsInGeneratedCode = true
    options.errorprone.disable("EmptyCatch")

    options.errorprone.error("FieldCanBeFinal")
}

tasks.withType<JavaCompile>().configureEach {
    if (name == "compileTestJava") {
        options.errorprone.disable("NullAway")

        options.errorprone.disable("FieldCanBeFinal")
    } else {
        options.errorprone {
            error("NullAway")
            option("NullAway:OnlyNullMarked", "true")
            option("NullAway:JSpecifyMode", "true")
        }
    }
}

checkstyle {
    toolVersion = "14.1.0"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}

dependencies {
    "errorprone"("com.google.errorprone:error_prone_core:2.50.0")
    "errorprone"("com.uber.nullaway:nullaway:0.14.1")
    compileOnly("org.jspecify:jspecify:1.0.0")
    testCompileOnly("org.jspecify:jspecify:1.0.0")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
