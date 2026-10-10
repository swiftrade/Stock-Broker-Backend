plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.jib)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.cloud.bom))

    implementation(libs.spring.cloud.starter.gateway)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.nimbus.jose.jwt)
    implementation(project(":libs:jwt-core"))
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation(libs.spring.boot.starter.test)
}

jib {
    to {
        image = "stockpredict/gateway:${project.version}"
    }
}
