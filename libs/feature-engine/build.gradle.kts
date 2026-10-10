plugins {
    `java-library`
}

// Pure-Java, no-Spring library: the port of STOCKMARKETANALYSIS/features.py.
// Owned by A3. Feature order/naming must come from the model metadata file,
// never hard-coded. Parity with Python is enforced by GoldenParityTest.

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
