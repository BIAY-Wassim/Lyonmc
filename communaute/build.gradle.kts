plugins {
    java
}

group = "fr.lyonmc"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    // LyonAchievements (compilé juste avant par Maven) : pièces, lots, profils, menus
    compileOnly(files("../target/classes"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = 21
    }
    processResources {
        filteringCharset = "UTF-8"
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }
    jar {
        archiveFileName = "COMMUNAUTE-${project.version}.jar"
        // Pas de code interne de Minecraft : Paper n'a pas besoin de remapper le plugin
        manifest { attributes("paperweight-mappings-namespace" to "mojang") }
    }
}
