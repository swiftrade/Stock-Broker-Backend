plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.security)
    api(libs.spring.boot.starter.validation)
    api(libs.spring.boot.starter.aop)
    api(libs.nimbus.jose.jwt)
    api(project(":libs:jwt-core"))
    api("io.micrometer:micrometer-registry-prometheus")

    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
}
