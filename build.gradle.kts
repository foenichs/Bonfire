plugins {
    kotlin("jvm") version "2.3.21"
    id("com.gradleup.shadow") version "9.4.1"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "com.foenichs"
version = "1.5.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://repo.bluecolored.de/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    compileOnly("de.bluecolored:bluemap-api:2.7.8")
    compileOnly("com.flowpowered:flow-math:1.0.3")
    compileOnly("xyz.jpenilla:squaremap-api:1.3.15")

    implementation("org.bstats:bstats-bukkit:3.2.1")
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.1.2")
    }
}

kotlin {
    jvmToolchain(25)
}

tasks.shadowJar {
    relocate("org.bstats", "com.foenichs.bonfire.lib.bstats")
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
