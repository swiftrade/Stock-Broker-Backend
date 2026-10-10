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
    implementation(project(":libs:feature-engine"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.xgboost4j)
    implementation(libs.caffeine)

    implementation(libs.swagger.annotations.jakarta)
    implementation(libs.jackson.databind.nullable)

    testImplementation(libs.spring.boot.starter.test)
}

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set(rootProject.file("contracts/prediction-service.yaml").absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    apiPackage.set("com.stockpredict.prediction.api")
    modelPackage.set("com.stockpredict.prediction.api.model")
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

jib {
    to {
        image = "stockpredict/prediction-service:${project.version}"
    }
}
