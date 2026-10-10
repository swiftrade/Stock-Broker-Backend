plugins {
    `java-library`
}

// End-to-end tests against a running stack (A8). Skipped unless a base URL is
// provided:
//   ./dev.sh :tests:e2e:test -Pe2eBaseUrl=http://localhost:8080 \
//       [-Pe2eToken=<premium access token>] \
//       [-Pe2eVerifyToken=<email verification token>]
// E2E_VERIFY_TOKEN is used as a fallback for -Pe2eVerifyToken.

dependencies {
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation("io.rest-assured:rest-assured:5.5.0")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
}

tasks.test {
    systemProperty("e2e.baseUrl", providers.gradleProperty("e2eBaseUrl").getOrElse(""))
    systemProperty("e2e.token", providers.gradleProperty("e2eToken").getOrElse(""))
    systemProperty("e2e.verifyToken", providers.gradleProperty("e2eVerifyToken")
        .orElse(providers.environmentVariable("E2E_VERIFY_TOKEN"))
        .getOrElse(""))
}
