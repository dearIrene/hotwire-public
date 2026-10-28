import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("hotwire.java")
    id("info.solidsoft.pitest") version "1.19.0"
}

val mixinCopy: SourceSet by sourceSets.creating
val mixinFixtures: SourceSet by sourceSets.creating
val mixinRuntime: Configuration by configurations.creating { isTransitive = false }

val mixin = "net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7"
val mixinExtras = "io.github.llamalad7:mixinextras-fabric:0.5.5"

dependencies {
    for (set in listOf("mixinCopyCompileOnly", "mixinFixturesCompileOnly")) {
        set(mixin)
        set(mixinExtras)
        set("org.ow2.asm:asm:9.8")
        set("org.ow2.asm:asm-tree:9.8")
        set("org.jspecify:jspecify:1.0.0")
    }
    for (part in listOf("asm", "asm-tree", "asm-commons", "asm-util", "asm-analysis")) {
        mixinRuntime("org.ow2.asm:$part:9.8")
    }
    mixinRuntime(mixin)
    mixinRuntime(mixinExtras)
    mixinRuntime("com.google.code.gson:gson:2.14.0")

    testImplementation(mixinFixtures.output)
    testRuntimeOnly(mixin)
    testRuntimeOnly(mixinExtras)
}

for (task in listOf("compileMixinCopyJava", "compileMixinFixturesJava")) {
    tasks.named<JavaCompile>(task) { options.errorprone.disable("NullAway") }
}

tasks.named<JavaCompile>("compileMixinFixturesJava") { options.errorprone.disable("UnusedMethod", "UnusedVariable") }

val mixinCopyJar = tasks.register<Jar>("mixinCopyJar") {
    from(mixinCopy.output)
    archiveFileName = "mixin-copy.jar"
    destinationDirectory = layout.buildDirectory.dir("generated/mixinCopy/dev/moon/hotwire/mixin_copy")

    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/mixinCopy"))
}
tasks.named("processResources") { dependsOn(mixinCopyJar) }

dependencies {
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    compileOnly("com.google.code.gson:gson:2.14.0")
    compileOnly("org.ow2.asm:asm:9.8")
    compileOnly("org.ow2.asm:asm-tree:9.8")
    compileOnly("org.ow2.asm:asm-commons:9.8")
    compileOnly("org.ow2.asm:asm-analysis:9.8")
    compileOnly("com.google.guava:guava:32.1.2-jre")

    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.ow2.asm:asm:9.8")
    testImplementation("org.ow2.asm:asm-tree:9.8")
    testImplementation("org.ow2.asm:asm-commons:9.8")
    testImplementation("org.ow2.asm:asm-analysis:9.8")
    testImplementation("com.google.guava:guava:32.1.2-jre")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

val agentJar = tasks.register<Jar>("agentJar") {
    archiveFileName = "hotwire-test-agent.jar"
    destinationDirectory = layout.buildDirectory.dir("agent")
    from(sourceSets.main.map { it.output }) { include("dev/moon/hotwire/jvm/AgentMain.class") }
    manifest {
        attributes(
            "Launcher-Agent-Class" to "dev.moon.hotwire.jvm.AgentMain",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
}
val agentJarPath = layout.buildDirectory.file("agent/hotwire-test-agent.jar").get().asFile.absolutePath

tasks.withType<Test>().configureEach {
    dependsOn(agentJar)
    systemProperty("hotwire.agentJar", agentJarPath)
    systemProperty("hotwire.mixinJars", mixinRuntime.asPath)
    systemProperty("hotwire.mixinFixtures", mixinFixtures.output.classesDirs.asPath + File.pathSeparator
            + mixinFixtures.output.resourcesDir!!.path)
    dependsOn(mixinFixtures.output)
    val testClasspath = sourceSets.test.get().runtimeClasspath
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dhotwire.testClasspath=" + testClasspath.asPath) })
}

val testOn21 = tasks.register<Test>("testOn21") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
}

pitest {
    targetClasses = listOf("dev.moon.hotwire.*")

    excludedClasses = listOf("dev.moon.hotwire.jvm.JvmAccess")

    excludedTestClasses = listOf("dev.moon.hotwire.jvm.FreshJvmTest", "dev.moon.hotwire.redefine.LiveRedefineTest",
            "dev.moon.hotwire.redefine.LiveRebuildTest", "dev.moon.hotwire.switching.LiveSwitchTest",
            "dev.moon.hotwire.api.ApiDumpTest", "dev.moon.hotwire.redefine.FieldSlotsTest",
            "dev.moon.hotwire.redefine.RebuildEvidenceTest")
    junit5PluginVersion = "1.2.3"
    mutationThreshold = 80
    timestampedReports = false
    enableDefaultIncrementalAnalysis = true
    threads = 4
    jvmArgs = listOf("-Dhotwire.agentJar=$agentJarPath", "-Dhotwire.mixinJars=" + mixinRuntime.asPath,
            "-Dhotwire.mixinFixtures=" + mixinFixtures.output.classesDirs.asPath + File.pathSeparator
                    + mixinFixtures.output.resourcesDir!!.path)
}

tasks.named("pitest") {
    dependsOn(agentJar)
}

tasks.named("check") {
    dependsOn(tasks.named("pitest"), testOn21)
}
