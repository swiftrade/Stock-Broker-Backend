plugins {
    `java-library`
}

// Resources-only module: packages the shared Flyway migrations
// (classpath:db/migration) so every service gets an identical schema.
