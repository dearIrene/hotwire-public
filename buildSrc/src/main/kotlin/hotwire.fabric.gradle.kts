import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("hotwire.java")
}

val minecraft = property("minecraft_version").toString()
val javaVersion = property("java_version").toString().toInt()

fun git(vararg args: String): String = providers.exec {
    commandLine(listOf("git") + args)
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

val commit = git("rev-parse", "--short=10", "HEAD").ifEmpty { "unknown" }
val dirty = git("status", "--porcelain", "--", rootProject.projectDir.path).isNotEmpty()
val committed = git("show", "-s", "--format=%cI", "HEAD")

val build = git("rev-list", "--count", "--since=${property("version_since")}", "HEAD").ifEmpty { "0" }
val stageVersion = "${property("mod_version")}.$build"
version = if (hasProperty("release")) "$stageVersion+mc$minecraft"
else "$stageVersion+mc$minecraft.$commit" + if (dirty) "-dirty." + LocalDateTime.now()
    .format(DateTimeFormatter.ofPattern("MMddHHmm")) else ""
base.archivesName = "hotwire"

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion
    options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-classfile"))

    options.errorprone.excludedPaths = ".*hotwire.fabric.(harness.)?mixin.*"
}

evaluationDependsOn(":common")
val common = project(":common")

dependencies {
    implementation(common)
}

tasks.named<Jar>("jar") {
    from(common.extensions.getByType<SourceSetContainer>()["main"].output)
    from(rootProject.file("LICENSE.md"))

    manifest {
        attributes(
            "Launcher-Agent-Class" to "dev.moon.hotwire.jvm.AgentMain",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
}

val modMenuApi = sourceSets.create("modmenuApi") {
    compileClasspath += configurations["compileClasspath"]
}
sourceSets["main"].compileClasspath += modMenuApi.output

val harness = sourceSets.create("harness") {
    val main = sourceSets["main"]
    compileClasspath += main.output + main.compileClasspath
    runtimeClasspath += main.output + main.runtimeClasspath
}
val harnessJar = tasks.register<Jar>("harnessJar") {
    from(harness.output)
    archiveClassifier = "harness"
    destinationDirectory = layout.buildDirectory.dir("harness")
}
tasks.named<ProcessResources>("processHarnessResources") {
    val expanded = mapOf("version" to version.toString())
    inputs.properties(expanded)
    filesMatching("fabric.mod.json") { expand(expanded) }
}
if (tasks.names.contains("remapJar")) {
    tasks.register<net.fabricmc.loom.task.RemapJarTask>("remapHarnessJar") {
        inputFile = harnessJar.flatMap { it.archiveFile }
        archiveClassifier = "harness"
        destinationDirectory = layout.buildDirectory.dir("libs")
    }
}

val releaseJar = tasks.named<AbstractArchiveTask>(if (tasks.names.contains("remapJar")) "remapJar" else "jar")
val collected = rootProject.layout.buildDirectory.dir("libs")
val collectJar = tasks.register<Copy>("collectJar") {
    doFirst {
        delete(fileTree(collected) { include("hotwire-*+$minecraft.jar", "hotwire-*+$minecraft.*.jar") })
    }
    from(releaseJar.flatMap { it.archiveFile })
    into(collected)
}

tasks.named<Jar>("jar") {
    doFirst {
        delete(fileTree(destinationDirectory) { include("hotwire-*.jar") })
    }
    exclude { it.file.path.contains("harness") }
}
tasks.named("build") { finalizedBy(collectJar) }

extensions.configure<net.fabricmc.loom.api.LoomGradleExtensionAPI> {
    runConfigs.configureEach { runDir = "../../run" }
}

tasks.named<ProcessResources>("processResources") {
    val range = if (minecraft.startsWith("1.")) minecraft else "~$minecraft"
    val expanded = mapOf("version" to version.toString(), "minecraft" to range, "java" to javaVersion.toString(),
            "commit" to commit, "dirty" to dirty.toString(), "committed" to committed)
    inputs.properties(expanded)
    filesMatching("fabric.mod.json") { expand(expanded) }
}
