plugins {
  java
}

group = "io.amscotti.example"
version = "0.1.0-SNAPSHOT"

// the library version under test: the aggregate check forwards -PreleaseVersion, so a
// release build consumes exactly the version being released, and local builds fall back
// to the development version the root build publishes
val braveSearchClientVersion = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

dependencies {
  implementation("io.amscotti:brave-search-client:$braveSearchClientVersion")

  testImplementation(platform("org.junit:junit-bom:6.1.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}
