plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.jib)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":common-lib"))
    implementation(project(":db-migrations"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    implementation(libs.swagger.annotations.jakarta)
    implementation(libs.jackson.databind.nullable)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation("org.apache.httpcomponents.client5:httpclient5")
}

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set(rootProject.file("contracts/auth-service.yaml").absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    apiPackage.set("com.stockpredict.auth.api")
    modelPackage.set("com.stockpredict.auth.api.model")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useSpringBoot3" to "true",
            "useTags" to "true",
            "skipDefaultInterface" to "true",
            "documentationProvider" to "none",
            "annotationLibrary" to "none",
            "useJakartaEe" to "true",
            "openApiNullable" to "false",
            "hideGenerationTimestamp" to "true",
        ),
    )
}

sourceSets {
    main {
        java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
    }
}

tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}

tasks.test {
    val includeIntegration = providers.gradleProperty("includeIntegrationTests").getOrElse("false")
    systemProperty("includeIntegrationTests", includeIntegration)
    if (includeIntegration == "true") {
        // docker-java defaults to API 1.32, which Docker >= 26 rejects.
        systemProperty("api.version", "1.44")
    }
}

jib {
    to {
        image = "stockpredict/auth-service:${project.version}"
    }
}
