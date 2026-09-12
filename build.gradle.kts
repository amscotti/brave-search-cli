import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.api.component.AdhocComponentWithVariants
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

group = "io.amscotti"
// tag builds pass the verified semantic version through -PreleaseVersion; local and CI
// builds stay on the development version until a tag says otherwise
version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

plugins {
  `java-library`
  application
  `maven-publish`
  jacoco
  alias(libs.plugins.graalvm.native)
  alias(libs.plugins.spotless)
  alias(libs.plugins.cyclonedx)
  `java-test-fixtures`
}

val braveSearchName = "brave-search"

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
  withSourcesJar()
  withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
  options.release = 25
  options.compilerArgs.add("-Xlint:all")
}

tasks.named<JavaCompile>("compileJava") {
  options.compilerArgs.add("-Aproject=io.amscotti/brave-search-cli")
}

application {
  applicationName = braveSearchName
  mainClass = "io.amscotti.bravesearch.bootstrap.Main"
}

distributions {
  named("main") {
    distributionBaseName = braveSearchName
  }
}

dependencies {
  api(platform(libs.jackson.bom))
  api(libs.jackson.databind)
  implementation(libs.picocli)
  annotationProcessor(libs.picocli.codegen)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.archunit.junit6)
  // test-only JSON Schema (draft 2020-12) validation of emitted machine documents;
  // its jackson-2 transitive dependency must never reach a main-scope configuration
  testImplementation(libs.json.schema.validator)

  testFixturesImplementation(platform(libs.junit.bom))
  testFixturesImplementation(libs.junit.jupiter)
  // schema validation shared by the JVM suites and the native process suite through the
  // testFixtures runtime variant; its jackson-2 transitives stay out of every main-scope
  // configuration
  testFixturesImplementation(libs.json.schema.validator)
}

val braveSearchVersion = version

tasks.named<ProcessResources>("processResources") {
  filesMatching("brave-search-version.properties") {
    expand(mapOf("version" to braveSearchVersion))
  }
}

graalvmNative {
  toolchainDetection = false
  binaries {
    named("main") {
      imageName = braveSearchName
      // the java-library plugin makes the native plugin default to a shared library;
      // this project ships an executable
      sharedLibrary = false
      buildArgs.add("--no-fallback")
      // the image builder is the hungriest single allocation in the build: cap its heap
      // explicitly (jvmArgs reach the builder JVM directly) so a memory-tight runner
      // slows the build instead of losing it to the kernel's out-of-memory killer
      jvmArgs.add("-Xmx4g")
    }
  }
  metadataRepository {
    enabled = true
  }
}

testing {
  suites {
    named<JvmTestSuite>("test") {
      useJUnitJupiter()
    }
    register<JvmTestSuite>("nativeSmokeTest") {
      useJUnitJupiter(libs.versions.junit.get())
      dependencies {
        implementation(project())
        implementation(testFixtures(project()))
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }
  }
}

tasks.withType<Test>().configureEach {
  // pin picocli's ANSI heuristics so NO_COLOR/CLICOLOR_FORCE/TERM cannot flip outcomes
  systemProperty("picocli.ansi", "false")
}

// test coverage of the JVM suite: the report (XML for tooling, HTML for readers) joins
// every `check` run next to the suite itself. The native process suite stays outside
// coverage — a native image cannot host the instrumenting agent.
jacoco {
  toolVersion = libs.versions.jacoco.get()
}

tasks.named<JacocoReport>("jacocoTestReport") {
  reports {
    xml.required = true
    html.required = true
  }
}

// the coverage floor of the JVM suite: the ratchet sits below the present 89.4% line
// and 81.9% branch coverage, so green stays green while any future drop trips `check`
// instead of rotting silently
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
  violationRules {
    rule {
      limit {
        counter = "LINE"
        minimum = "0.85".toBigDecimal()
      }
      limit {
        counter = "BRANCH"
        minimum = "0.78".toBigDecimal()
      }
    }
  }
}

tasks.named("check") {
  dependsOn("jacocoTestReport", "jacocoTestCoverageVerification")
}

// opt-in live protocol smoke: the live-tagged suite stays out of every default run (zero
// external calls) and joins only when -PliveBraveTests=true explicitly asks for it; an
// opted-in run is serial by the live-testing policy (docs/release.md): single-fork execution
// plus the live guard's class-level exchange lock
val liveBraveTests = providers.gradleProperty("liveBraveTests").map { it == "true" }.getOrElse(false)

tasks.named<Test>("test") {
  maxParallelForks = 1
  useJUnitPlatform {
    if (!liveBraveTests) {
      excludeTags("live")
    }
  }
  if (liveBraveTests) {
    // the live tests emit only bounded, redacted structural observations; surfaces them in
    // the console so an opted-in run's recorded findings are visible without report digging
    testLogging {
      showStandardStreams = true
    }
  }
}

val nativeBinary =
  tasks
    .named<org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask>("nativeCompile")
    .flatMap { it.outputFile }

val jvmLauncherScript =
  layout.buildDirectory.file("install/$braveSearchName/bin/$braveSearchName")

// release packaging: one target-triple archive per host (naming pinned by
// ReleaseArchiveVerificationTest), a runtime-scoped SBOM beside it, and SHA256SUMS covering
// every artifact in the release directory. The host triple is a provider, so the
// unsupported-host guard throws only at execution, never at configuration: hosts that
// cannot cut releases (the pinned toolchains include one for windows-x64) still configure
// every task. Their runs stop there even for a plain `test`, because the release
// verification below is wired into the test task itself — the full suite belongs to hosts
// with a supported macOS/Linux triple
val releaseOs =
  providers.systemProperty("os.name").map { osName ->
    when (osName.lowercase()) {
      "mac os x", "macos" -> "macos"
      "linux" -> "linux"
      else -> throw GradleException("unsupported release host operating system: $osName")
    }
  }
val releaseArch =
  providers.systemProperty("os.arch").map { arch ->
    when (arch) {
      "aarch64" -> "aarch64"
      "amd64", "x86_64" -> "x86_64"
      else -> throw GradleException("unsupported release host architecture: $arch")
    }
  }
val releaseTriple = releaseOs.zip(releaseArch) { os, arch -> "$os-$arch" }
val releaseArchiveStem = releaseTriple.map { triple -> "$braveSearchName-$version-$triple" }
val releaseDirectory = layout.buildDirectory.dir("release")
val releaseArchivePath =
  releaseDirectory.flatMap { directory ->
    releaseArchiveStem.map { stem -> directory.file("$stem.tar.gz") }
  }

tasks.named<Test>("test") {
  dependsOn(tasks.named("installDist"))
  dependsOn(tasks.named("sha256Sums"))
  inputs.file(jvmLauncherScript)
  // the release artifacts under verification are declared inputs, not just task
  // dependencies, so a stale archive or checksum file fails up-to-date checking
  inputs.file(releaseArchivePath)
  inputs.file(releaseDirectory.map { it.file("SHA256SUMS") })
  // resolve the installed JVM launcher and release paths at execution time (after the
  // producing tasks ran)
  doFirst {
    systemProperty("brave.search.jvm.launcher", jvmLauncherScript.get().asFile.absolutePath)
    systemProperty("brave.search.release.archive", releaseArchivePath.get().asFile.absolutePath)
    systemProperty("brave.search.release.dir", releaseDirectory.get().asFile.absolutePath)
  }
}

val runtimeSbom =
  tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom").flatMap { it.jsonOutput }

tasks.register<Tar>("releaseArchive") {
  group = "distribution"
  description =
    "Packages the native binary, README, LICENSE, completions, agent skill, machine output schemas, " +
    "published library jars, and the runtime-only SBOM into one reproducible target-triple tar.gz"
  dependsOn(tasks.named("nativeCompile"))
  dependsOn(tasks.named("publishLibraryPublicationToMavenLocal"))
  compression = Compression.GZIP
  destinationDirectory = releaseDirectory
  archiveFileName = releaseArchiveStem.map { "$it.tar.gz" }
  // sorted entries and fixed timestamps come from the repository-wide archive settings; the
  // modes are pinned here so the binary's executable bit survives every packager
  into(releaseArchiveStem)
  dirPermissions { unix(0b111_101_101) }
  filePermissions { unix(0b110_100_100) }
  into("bin") {
    from(nativeBinary)
    filePermissions { unix(0b111_101_101) }
  }
  into("completions") {
    from("src/main/resources/completions")
  }
  into("skills/brave-search") {
    from("skills/brave-search/SKILL.md")
  }
  into("schemas") {
    from("schemas")
    include("**/*.schema.json")
  }
  into("lib") {
    from(
      File(
        File(System.getProperty("user.home"), ".m2/repository/io/amscotti/brave-search-client"),
        version.toString(),
      ),
    )
    include("*.jar")
  }
  into("sbom") {
    from(runtimeSbom)
    rename { "bom.json" }
  }
  from("README.md")
  from("LICENSE")
}

tasks.register<Copy>("releaseSbom") {
  group = "distribution"
  description = "Places the runtime-only CycloneDX SBOM beside the archive under its platform name"
  from(runtimeSbom)
  into(releaseDirectory)
  // rename closures run while copying, so realizing the archive stem here keeps the
  // unsupported-host guard out of configuration
  rename { "${releaseArchiveStem.get()}.sbom.json" }
}

tasks.register("sha256Sums") {
  group = "distribution"
  description = "Writes SHA256SUMS covering every artifact in the release directory, sorted by name"
  dependsOn(tasks.named("releaseArchive"))
  dependsOn(tasks.named("releaseSbom"))
  val sumsOutput = releaseDirectory.map { it.file("SHA256SUMS") }
  outputs.file(sumsOutput)
  // the covered artifacts are declared inputs, so a repacked archive or refreshed SBOM
  // invalidates the manifest instead of leaving a digest of the previous bytes
  inputs.files(
    fileTree(releaseDirectory) { exclude("SHA256SUMS") },
  )
  doLast {
    val directory = releaseDirectory.get().asFile
    val artifacts =
      directory
        .listFiles { file -> file.isFile && file.name != "SHA256SUMS" }
        .orEmpty()
        .sortedBy { it.name }
    if (artifacts.isEmpty()) {
      throw GradleException("no release artifacts found in $directory")
    }
    val text =
      artifacts.joinToString("") { artifact ->
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
          }
        }
        HexFormat.of().formatHex(digest.digest()) + "  " + artifact.name + "\n"
      }
    sumsOutput.get().asFile.writeText(text)
    logger.lifecycle("wrote ${sumsOutput.get().asFile} covering ${artifacts.size} artifact(s)")
  }
}

tasks.named<Test>("nativeSmokeTest") {
  dependsOn(tasks.named("nativeCompile"))
  inputs.file(nativeBinary)
  // resolve the native binary path at execution time (after nativeCompile ran): the plain
  // system-properties map does not unwrap providers, and realizing it eagerly would defeat
  // the provider wiring
  doFirst {
    systemProperty("brave.search.native.binary", nativeBinary.get().asFile.absolutePath)
  }
}

// phase-hygiene gate: rejects build-step terminology in durable artifacts by running the
// unit-tested scanner from the test source set over the repository root
val hygieneCheck =
  tasks.register<JavaExec>("hygieneCheck") {
    group = "verification"
    description = "Rejects build-step terminology (numbered work items, stage references, planning-file mentions) in durable artifacts"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "io.amscotti.bravesearch.hygiene.HygieneMain"
    args(rootDir)
  }

dependencyLocking {
  lockAllConfigurations()
}

tasks.register("resolveAndLockAll") {
  notCompatibleWithConfigurationCache("resolves every resolvable configuration at execution time to write lock state")
  doLast {
    configurations
      .filter { it.isCanBeResolved }
      .forEach { it.resolve() }
  }
}

// verification-metadata completeness gate: every component the committed lock state pins
// must carry an entry in gradle/verification-metadata.xml. Regenerating metadata from a
// warm cache can silently under-cover the graph — artifacts already cached are not
// re-verified, so anything nobody re-downloaded never gains its entry and only a fresh
// clone fails strict verification. The gate compares the two committed files directly, so
// it costs no resolution and runs in every check. Parent POMs sit outside both files by
// construction (they are metadata, not graph components), so coverage of those is proven
// by the cold resolve: the CI dependency job resolves the tree in a virgin Gradle user
// home, where every artifact — parents included — downloads under strict verification.
val verifyMetadataComplete =
  tasks.register("verifyMetadataComplete") {
    group = "verification"
    description =
      "Fails when a component pinned by the committed lock state lacks an entry in " +
      "gradle/verification-metadata.xml, so warm-cache metadata regeneration cannot silently under-cover the graph"
    val metadataFile = rootDir.resolve("gradle/verification-metadata.xml")
    val lockfiles =
      listOf(
        rootDir.resolve("gradle.lockfile"),
        rootDir.resolve("settings-gradle.lockfile"),
      )
    inputs.file(metadataFile)
    inputs.files(lockfiles)
    doLast {
      val recorded =
        Regex("<component group=\"([^\"]+)\" name=\"([^\"]+)\" version=\"([^\"]+)\">")
          .findAll(metadataFile.readText())
          .mapTo(mutableSetOf()) { match -> match.groupValues.drop(1).joinToString(":") }
      val locked =
        lockfiles
          .flatMap { file ->
            Regex("^([^=#:]+:[^=#:]+:[^=#]+)=", RegexOption.MULTILINE)
              .findAll(file.readText())
              .map { it.groupValues[1] }
          }.toSortedSet()
      val missing = locked - recorded
      if (missing.isNotEmpty()) {
        throw GradleException(
          buildString {
            appendLine(
              "dependency verification metadata is incomplete: ${missing.size} locked component(s) lack entries " +
                "in gradle/verification-metadata.xml:",
            )
            missing.sorted().forEach { component -> appendLine("  $component") }
            append(
              "regenerate from a cold cache over a checksum-only policy: GRADLE_USER_HOME=<fresh> ./gradlew " +
                "--no-daemon --write-verification-metadata sha256 <resolve task>",
            )
          },
        )
      }
      logger.lifecycle(
        "verification metadata covers all ${locked.size} locked component(s) (${recorded.size} entries)",
      )
    }
  }

tasks.named("check") {
  dependsOn(verifyMetadataComplete)
}

// tampered-dependency proof: a scratch resolution build resolves the same runtime coordinates
// under the repository's committed verification metadata, first against an honest mirror of
// the cached picocli artifacts (must succeed), then against a one-byte-flipped copy of the jar
// (must fail dependency verification). The drill builds in its own directory and Gradle user
// home, so the real caches and lock state are never mutated; its evidence lands in
// build/reports/dependency-tamper-proof/evidence.txt
tasks.register("dependencyTamperProof") {
  group = "verification"
  description =
    "Proves the committed dependency verification rejects a tampered artifact: one honest and one " +
    "corrupted resolution run against a scratch Gradle user home, with evidence in the report directory"
  notCompatibleWithConfigurationCache("drives an external Gradle build in a scratch user home")
  doLast {
    val picocliVersion = libs.versions.picocli.get()
    val jacksonVersion = libs.versions.jackson.get()
    val drillRoot = rootDir.resolve("build/dependency-tamper-proof")
    val repoDir = drillRoot.resolve("repo")
    val projectDir = drillRoot.resolve("project")
    val scratchHome = drillRoot.resolve("home")
    for (stale in listOf(drillRoot.resolve("project"), drillDir(repoDir, picocliVersion), repoDir)) {
      stale.deleteRecursively()
    }
    repoDir.mkdirs()
    projectDir.mkdirs()
    scratchHome.mkdirs()

    // honest artifact copies come from the local cache: the jar via this build's own locked
    // resolution, the pom and module metadata via the cache tree beside it
    val cachedJar =
      configurations.runtimeClasspath
        .get()
        .files
        .firstOrNull { it.name == "picocli-$picocliVersion.jar" }
        ?: throw GradleException("picocli-$picocliVersion.jar is not in the local cache; run a build first")
    val picocliCache =
      File(File(System.getProperty("user.home"), ".gradle"), "caches/modules-2/files-2.1/info.picocli/picocli")
    val repoArtifactDir = drillDir(repoDir, picocliVersion)
    repoArtifactDir.mkdirs()
    cachedJar.copyTo(repoArtifactDir.resolve(cachedJar.name), overwrite = true)
    for (metadata in listOf("pom", "module")) {
      picocliCache
        .walkTopDown()
        .filter { it.isFile && it.name == "picocli-$picocliVersion.$metadata" }
        .firstOrNull()
        ?.copyTo(repoArtifactDir.resolve("picocli-$picocliVersion.$metadata"), overwrite = true)
    }
    if (!repoArtifactDir.resolve("picocli-$picocliVersion.pom").isFile) {
      throw GradleException("picocli-$picocliVersion.pom is not in the local cache; run a build first")
    }

    projectDir.resolve("settings.gradle").writeText("rootProject.name = 'dependency-tamper-proof-drill'\n")
    projectDir.resolve("build.gradle").writeText(
      """
      plugins { id 'java' }
      repositories {
        mavenCentral()
        exclusiveContent {
          forRepository { maven { url = uri('${repoDir.toURI()}') } }
          filter { includeGroup 'info.picocli' }
        }
      }
      dependencies {
        implementation 'info.picocli:picocli:$picocliVersion'
        implementation 'tools.jackson.core:jackson-databind:$jacksonVersion'
      }
      tasks.register('resolveRuntimeArtifacts') {
        doLast { configurations.runtimeClasspath.resolve().each { println 'RESOLVED ' + it.name } }
      }
      """.trimIndent() + "\n",
    )
    projectDir.resolve("gradle").mkdirs()
    rootDir.resolve("gradle/verification-metadata.xml").copyTo(
      projectDir.resolve("gradle/verification-metadata.xml"),
      overwrite = true,
    )

    // reuse the wrapper distribution already on this machine when present, so the drill pays
    // one artifact download, not one distribution download
    val realDists = File(File(System.getProperty("user.home"), ".gradle"), "wrapper/dists")
    if (realDists.isDirectory) {
      val distsLink = scratchHome.resolve("wrapper/dists")
      distsLink.parentFile.mkdirs()
      if (!distsLink.exists()) {
        try {
          Files.createSymbolicLink(distsLink.toPath(), realDists.toPath())
        } catch (unsupported: UnsupportedOperationException) {
          // the drill downloads the distribution itself when the filesystem provider cannot
          // create symlinks at all
        } catch (denied: IOException) {
          // and when the host refuses one: Windows without the symlink privilege fails here
          // with FileSystemException, an IOException, not UnsupportedOperationException
        }
      }
    }

    fun resolveArtifacts(): Pair<Int, String> {
      val builder =
        ProcessBuilder(
          "./gradlew",
          "--no-daemon",
          "--console=plain",
          "-p",
          projectDir.absolutePath,
          "resolveRuntimeArtifacts",
        ).directory(rootDir)
          .redirectErrorStream(true)
      // the child resolves in the scratch Gradle user home; pointed at the real one, the
      // drill would resolve against the maintainer caches and its isolation claim is false
      builder.environment()["GRADLE_USER_HOME"] = scratchHome.absolutePath
      val process = builder.start()
      // drain the child's combined output on a background thread (as the project's
      // ProcessHarness does): reading it on this thread would block until the child closes
      // its output, so a hung child Gradle would outlive the timeout below unenforced
      val outputSink = ByteArrayOutputStream()
      val drain =
        Thread.ofVirtual().start {
          try {
            process.inputStream.use { input -> input.copyTo(outputSink) }
          } catch (failedDrain: IOException) {
            // a killed child closes its pipes abruptly; drained bytes are kept
          }
        }
      val finished = process.waitFor(10, TimeUnit.MINUTES)
      if (!finished) {
        process.destroyForcibly()
        process.waitFor(1, TimeUnit.MINUTES)
        drain.join(10_000)
        throw GradleException("the tamper drill build did not finish within ten minutes")
      }
      drain.join(10_000)
      return process.exitValue() to outputSink.toString(Charsets.UTF_8)
    }

    val (honestExit, honestOutput) = resolveArtifacts()
    val resolved = honestOutput.lines().filter { it.startsWith("RESOLVED ") }.sorted()

    // flip one byte of the mirrored jar and evict the cached copy, so the next run must fetch
    // the corrupted artifact and verify it against the committed checksums
    val jarBytes = repoArtifactDir.resolve(cachedJar.name).readBytes()
    jarBytes[jarBytes.size - 1] = (jarBytes.last().toInt() xor 0x5A).toByte()
    repoArtifactDir.resolve(cachedJar.name).writeBytes(jarBytes)
    File(scratchHome, "caches/modules-2/files-2.1/info.picocli").deleteRecursively()

    val (tamperedExit, tamperedOutput) = resolveArtifacts()
    val failureLines =
      tamperedOutput.lines().filter { it.contains("verification", ignoreCase = true) }.take(6)

    val evidence = drillRoot.parentFile.resolve("reports/dependency-tamper-proof")
    evidence.mkdirs()
    val report =
      buildString {
        appendLine("dependency-tamper-proof evidence")
        appendLine("date: ${Instant.now()}")
        appendLine("verified coordinates: info.picocli:picocli:$picocliVersion, tools.jackson.core:jackson-databind:$jacksonVersion")
        appendLine("verification metadata: gradle/verification-metadata.xml (copied unmodified into the drill)")
        appendLine("gradle user home: ${scratchHome.absolutePath} (isolated through GRADLE_USER_HOME)")
        appendLine()
        appendLine("honest mirror run: exit $honestExit")
        resolved.forEach { appendLine("  $it") }
        appendLine()
        appendLine("tampered jar run (last byte of ${cachedJar.name} flipped): exit $tamperedExit")
        if (failureLines.isEmpty()) {
          appendLine("  no verification-failure lines captured; full output follows")
          tamperedOutput.lines().take(40).forEach { appendLine("  | $it") }
        } else {
          failureLines.forEach { appendLine("  $it") }
        }
      }
    evidence.resolve("evidence.txt").writeText(report)

    val failedAsExpected = tamperedExit != 0 && failureLines.isNotEmpty()
    if (honestExit != 0 || !failedAsExpected) {
      throw GradleException(
        "the tamper drill did not behave as required (honest exit $honestExit, tampered exit " +
          "$tamperedExit, verification-failure lines captured: ${failureLines.size}); see " +
          "${evidence.resolve("evidence.txt")}",
      )
    }
    logger.lifecycle("tamper proof: honest run exit 0, corrupted run failed dependency verification")
    logger.lifecycle("evidence: ${evidence.resolve("evidence.txt")}")
  }
}

/** The Maven-layout directory one artifact lives in: {@code <repo>/<groupPath>/<version>}. */
fun drillDir(
  repo: File,
  version: String,
): File = repo.resolve("info/picocli/picocli/$version")

tasks.withType<AbstractArchiveTask>().configureEach {
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}

val checkPicocliNativeMetadata =
  tasks.register("checkPicocliNativeMetadata") {
    val jarArchive = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    dependsOn(tasks.named<Jar>("jar"))
    inputs.file(jarArchive)
    doLast {
      val jarFile = jarArchive.get().asFile
      ZipFile(jarFile).use { zip ->
        val entries = zip.entries()
        var found = false
        while (entries.hasMoreElements()) {
          if (entries.nextElement().name.startsWith("META-INF/native-image/picocli-generated/")) {
            found = true
            break
          }
        }
        if (!found) {
          throw GradleException(
            "JAR $jarFile lacks META-INF/native-image/picocli-generated/ entries; " +
              "picocli-codegen annotation processor output is missing",
          )
        }
      }
    }
  }

tasks.named("nativeCompile") {
  dependsOn(checkPicocliNativeMetadata)
}

// the release SBOM is production-runtime-only: the direct task resolves exactly the
// runtimeClasspath configuration (no test, fixtures, or annotation-processor scopes) and is
// emitted without a serial number so two builds of one tree produce identical SBOM bytes
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
  projectType = Component.Type.APPLICATION
  includeConfigs = listOf("runtimeClasspath")
  includeBomSerialNumber = false
}

// the library advertises exactly three artifacts — the plain jar, sources, and javadoc —
// so the test-fixtures variant that only the repository's own native suite consumes stays
// out of the publication
components.named<AdhocComponentWithVariants>("java") {
  withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
  withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}

publishing {
  publications {
    create<MavenPublication>("library") {
      from(components["java"])
      artifactId = "brave-search-client"
      pom {
        name = "Brave Search Client"
        description =
          "Java library for the Brave Search API: typed per-endpoint responses with " +
          "caller-owned lossless upstream snapshots, streaming Answers with public " +
          "cancellation, and explicit caller-supplied credentials"
        url = "https://github.com/amscotti/brave-search-cli"
        licenses {
          license {
            name = "Apache-2.0"
            url = "https://www.apache.org/licenses/LICENSE-2.0"
          }
        }
        developers {
          developer {
            id = "amscotti"
            name = "amscotti"
            url = "https://github.com/amscotti"
          }
        }
        scm {
          // pre-repository placeholder: the public repository does not exist yet, so this
          // is the intended home stated up front and corrected here the moment the
          // repository is created
          url = "https://github.com/amscotti/brave-search-cli"
          connection = "scm:git:https://github.com/amscotti/brave-search-cli.git"
          developerConnection = "scm:git:git@github.com:amscotti/brave-search-cli.git"
        }
      }
    }
  }
  repositories {
    // release hosting; the credentials arrive through the environment and are optional for
    // local builds, which only ever need publishToMavenLocal
    maven {
      name = "GitHubPackages"
      url =
        uri(
          providers
            .environmentVariable("GITHUB_PACKAGES_URL")
            .orElse("https://maven.pkg.github.com/amscotti/brave-search-cli")
            .get(),
        )
      credentials {
        username = providers.environmentVariable("GITHUB_ACTOR").orNull
        password = providers.environmentVariable("GITHUB_TOKEN").orNull
      }
    }
  }
}

// release-packaging verification: the published coordinates carry the library classes, the
// api sources, the generated api docs, and the pom facts consumers see
val libraryVersion = version.toString()

val verifyLibraryPublication =
  tasks
    .register("verifyLibraryPublication") {
      group = "verification"
      description = "Verifies the mavenLocal publication of brave-search-client: artifacts, contents, and pom facts"
      dependsOn(tasks.named("publishToMavenLocal"))
      doLast {
        val mavenLocalBase =
          File(
            File(System.getProperty("user.home"), ".m2/repository/io/amscotti/brave-search-client"),
            libraryVersion,
          )
        val main = File(mavenLocalBase, "brave-search-client-$libraryVersion.jar")
        val sources = File(mavenLocalBase, "brave-search-client-$libraryVersion-sources.jar")
        val javadoc = File(mavenLocalBase, "brave-search-client-$libraryVersion-javadoc.jar")
        val pom = File(mavenLocalBase, "brave-search-client-$libraryVersion.pom")
        for (artifact in listOf(main, sources, javadoc, pom)) {
          if (!artifact.isFile) {
            throw GradleException("publication is incomplete: $artifact is missing")
          }
        }
        val fixtures = File(mavenLocalBase, "brave-search-client-$libraryVersion-test-fixtures.jar")
        if (fixtures.isFile) {
          throw GradleException("publication leaks the test-fixtures artifact: $fixtures")
        }
        requireEntries(main, "io/amscotti/bravesearch/api/BraveSearchClient.class", "the library classes")
        requireEntries(main, "io/amscotti/bravesearch/api/PublicStreamFrame.class", "the public streaming surface")
        requireEntries(main, "io/amscotti/bravesearch/domain/request/WebSearchRequest.class", "the domain requests")
        requireEntries(main, "io/amscotti/bravesearch/application/port/out/WebSearchPort.class", "the application ports")
        requireEntries(main, "io/amscotti/bravesearch/adapter/bravehttp/BraveHttpTransport.class", "the http adapter")
        forbidEntries(main, "picocli/", "the CLI framework must stay out of the library jar")
        requireEntries(sources, "io/amscotti/bravesearch/api/BraveSearchClient.java", "the api sources")
        requireEntries(javadoc, "io/amscotti/bravesearch/api/BraveSearchClient.html", "the generated api docs")
        val pomText = pom.readText()
        for (required in listOf("<name>Brave Search Client</name>", "Apache-2.0", "<scope>compile</scope>")) {
          if (!pomText.contains(required)) {
            throw GradleException("pom of brave-search-client lacks the fact consumers rely on: $required")
          }
        }
        if (!pomText.contains("<artifactId>jackson-databind</artifactId>")) {
          throw GradleException("pom of brave-search-client lacks the public Jackson dependency")
        }
        for (required in listOf("<developers>", "<developer>", "<id>amscotti</id>", "<scm>")) {
          if (!pomText.contains(required)) {
            throw GradleException("pom of brave-search-client lacks the fact consumers rely on: $required")
          }
        }
        if (!pomText.contains("<connection>scm:git:https://github.com/amscotti/brave-search-cli.git</connection>")) {
          throw GradleException("pom of brave-search-client lacks the scm connection")
        }
      }
    }

fun requireEntries(
  archive: File,
  entry: String,
  what: String,
) {
  ZipFile(archive).use { zip ->
    if (zip.getEntry(entry) == null) {
      throw GradleException("$archive lacks $entry ($what)")
    }
  }
}

fun forbidEntries(
  archive: File,
  prefix: String,
  reason: String,
) {
  ZipFile(archive).use { zip ->
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
      val name = entries.nextElement().name
      if (name.startsWith(prefix)) {
        throw GradleException("$archive contains $name: $reason")
      }
    }
  }
}

// the independent sample consumer compiles and runs against only the published artifact:
// publish first, then let the consumer's own build resolve it from mavenLocal. The release
// version travels through -PreleaseVersion, so a tagged release build validates the consumer
// against exactly the version being released instead of the development fallback
val sampleConsumerCheck =
  tasks
    .register<Exec>("sampleConsumerCheck") {
      group = "verification"
      description = "Publishes the library to mavenLocal and runs the independent sample-consumer suite against it"
      dependsOn(tasks.named("publishToMavenLocal"))
      workingDir = rootDir.resolve("sample-consumer")
      commandLine(
        "${rootDir.resolve("sample-consumer/gradlew").absolutePath}",
        "--no-daemon",
        "--console=plain",
        // the nested consumer build runs inside the same runner as everything else:
        // bound its workers like the hosted invocations so it cannot join a
        // parallelism pile-up that starves the runner agent
        "--max-workers=2",
        "-PreleaseVersion=$version",
        "test",
      )
    }

tasks.named("check") {
  dependsOn(tasks.named("nativeSmokeTest"))
  dependsOn(hygieneCheck)
  dependsOn(verifyLibraryPublication)
  dependsOn(sampleConsumerCheck)
}

spotless {
  // palantir-java-format (latest on Maven Central, 2.68.0) fails on JDK 25 with
  // NoSuchMethodError in its javac shim, so conservative built-in steps format Java until an
  // engine supporting JDK 25 is available.
  java {
    // the root tree and the independent sample-consumer build's tree: the root build is the
    // repository's one formatting authority, so both source trees answer to it
    target("src/*/java/**/*.java", "sample-consumer/src/*/java/**/*.java")
    importOrder()
    removeUnusedImports()
    trimTrailingWhitespace()
    endWithNewline()
    leadingTabsToSpaces(4)
  }
  kotlinGradle {
    target("*.gradle.kts", "sample-consumer/*.gradle.kts")
    ktlint("1.3.1")
  }
  format("auxiliary") {
    target(
      ".gitignore",
      ".gitattributes",
      ".editorconfig",
      ".env.example",
      "*.properties",
      "*.toml",
      "gradle/libs.versions.toml",
      "mise.lock",
      "gradle/wrapper/gradle-wrapper.properties",
      "sample-consumer/gradle/wrapper/gradle-wrapper.properties",
    )
    trimTrailingWhitespace()
    endWithNewline()
  }
  format("structured") {
    // repo-wide YAML and JSON: workflows, the schemas, the contract index, and the shaped
    // test fixtures all answer to the same whitespace contract
    target("**/*.yml", "**/*.yaml", "**/*.json")
    // generated build outputs, local tool caches, version-control internals, the review
    // harness's scratch directory, and the committed Gradle lock/verification state are not
    // hand-maintained surfaces and must never be reformatted
    targetExclude(
      "**/build/**",
      "**/.gradle/**",
      "**/.git/**",
      ".review/**",
      "gradle.lockfile",
      "gradle/verification-metadata.xml",
    )
    trimTrailingWhitespace()
    endWithNewline()
  }
  format("markdown") {
    target("docs/**/*.md")
    // trailing whitespace stays untouched: .editorconfig preserves it in Markdown because
    // two trailing spaces are a hard line break, and stripping them would silently reflow
    // paragraphs
    endWithNewline()
  }
}
