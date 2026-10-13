tasks.register("quick") {
    group = "verification"
    description = "Tests common and compiles the active Minecraft version."
    dependsOn(":common:test", ":fabric:26.3:compileJava")
}
