plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.nimbus.jose.jwt)
}
