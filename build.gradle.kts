import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask
import org.jetbrains.intellij.platform.gradle.tasks.GenerateLexerTask
import org.jetbrains.intellij.platform.gradle.tasks.GenerateParserTask
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    idea
    jacoco
    id("org.jetbrains.intellij.platform") version "2.20.1"
    id("org.jetbrains.changelog") version "2.5.0"
    id("org.jetbrains.intellij.platform.grammarkit") version "2.20.1"
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "software.amazon.smithy"
version = "2.0.20"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-jvm-default=enable")
    }
}

sourceSets {
    main {
        java.srcDir("src/main/gen")
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        sourceSets.main.get().output.classesDirs.asFileTree.matching { exclude("**/psi/impl/**") }
    )
    val reportIndex = reports.html.outputLocation.file("index.html")
    doLast {
        println("Coverage Report: file://${reportIndex.get()}")
    }
}

idea {
    module {
        generatedSourceDirs.add(file("src/main/gen"))
    }
}

// Jackson is no longer provided by the IDE (2026.3 moved Jackson 2 out of the core classpath), so it is bundled into
// the plugin jar and relocated to avoid clashing with whatever version the IDE ships.
val shaded = configurations.create("shaded")
configurations {
    compileOnly { extendsFrom(shaded) }
    testImplementation { extendsFrom(shaded) }
}

dependencies {
    shaded("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    shaded("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1") {
        // Kotlin is provided by the IDE
        exclude(group = "org.jetbrains.kotlin")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("software.amazon.smithy:smithy-model:1.49.0")

    intellijPlatform {
        intellijIdea("2026.3")
        // Split out of the core platform into separately-loaded modules/plugins in 2026.x
        bundledModule("intellij.spellchecker")
        bundledPlugin("com.intellij.structureView")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    // Kotlin sources only; nothing to instrument, and the shadow jar is built from the plain compiled classes.
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263"
            untilBuild = provider { null }
        }
        changeNotes = provider { changelog.renderItem(changelog.getLatest(), Changelog.OutputType.HTML) }
    }
    pluginVerification {
        // The plugin ID predates this rule and is already published on the Marketplace, so it can't be changed
        freeArgs = listOf("-mute", "TemplateWordInPluginId")
        ides {
            recommended()
        }
    }
}

tasks.jar {
    archiveClassifier = "thin"
}

// Unused (the sandboxes use shadowJar instead), but must not write to the same path as shadowJar
tasks.named<ComposedJarTask>("composedJar") {
    archiveClassifier = "composed"
}

tasks.shadowJar {
    archiveClassifier = ""
    configurations = listOf(shaded)
    relocate("com.fasterxml.jackson", "software.amazon.smithy.intellij.shaded.jackson")
    // Drop multi-release variants and module descriptors from the shaded dependencies
    exclude("module-info.class", "META-INF/versions/**")
    // Duplicates are dropped, except for files the service/kotlin_module transformers need to see in order to merge
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
    filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    failOnDuplicateEntries = true
}

// Every sandbox (runIde, tests, verification) must use the shaded jar so the relocated Jackson classes are present
tasks.withType<PrepareSandboxTask>().configureEach {
    pluginJar = tasks.shadowJar.flatMap { it.archiveFile }
}

tasks.named<GenerateLexerTask>("generateLexer") {
    sourceFile = file("src/main/grammar/Smithy.flex")
    targetRootOutputDir = file("src/main/gen")
    purgeOldFiles = true
}

tasks.named<GenerateParserTask>("generateParser") {
    sourceFile = file("src/main/grammar/Smithy.bnf")
    targetRootOutputDir = file("src/main/gen")
    pathToParser = "software/amazon/smithy/intellij/SmithyParser.java"
    pathToPsiRoot = "software/amazon/smithy/intellij/psi"
    purgeOldFiles = true
    // Grammar-Kit's Java keyword escaping omits the `null` literal, so the `null` rule is emitted as an invalid
    // `null(...)` method. Escape it the same way Grammar-Kit escapes other keywords (e.g. `boolean_$`).
    val parser = file("src/main/gen/software/amazon/smithy/intellij/SmithyParser.java")
    doLast {
        parser.writeText(parser.readText().replace(Regex("""\bnull\((PsiBuilder |builder_)"""), """null_\$($1"""))
    }
}

listOf("compileKotlin", "compileJava").forEach {
    tasks.named(it) { dependsOn("generateLexer", "generateParser") }
}
