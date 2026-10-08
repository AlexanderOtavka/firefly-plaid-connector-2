import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Paths

val kotlinVersion: String by project
val kotlinCoroutinesVersion: String by project
val exposedVersion: String by project
val ktorVersion: String by project
val jacksonVersion: String by project

plugins {
    id("org.openapi.generator") version "7.7.0"
    id("org.springframework.boot") version "3.3.2"
    id("io.spring.dependency-management") version "1.1.6"
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

group = "net.djvk"
version = "1.5.1"
java.sourceCompatibility = JavaVersion.VERSION_17

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.bundles.openapi)
    implementation(libs.ktor.cio)
    implementation(libs.ktor.logging)
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.semver4j:semver4j:5.3.0")

    // Local change: the management dashboard (src/manage/). Batch and polled modes keep
    // running without a web server or a database; see application.yml.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.fabric8:kubernetes-client:6.13.4")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.zonky.test:embedded-postgres:2.0.7")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.mock)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

var generatePlaidClient = tasks.register<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("generatePlaidClient") {
    generatorName.set("kotlin")
    inputSpec.set(layout.projectDirectory.dir("specs").file("plaid-2020-09-14.yml").toString())
    cleanupOutput.set(true)
    outputDir.set(layout.buildDirectory.dir("generated-plaid").get().toString())
    apiPackage.set("net.djvk.fireflyPlaidConnector2.api.plaid.apis")
    invokerPackage.set("net.djvk.fireflyPlaidConnector2.api.plaid.invoker")
    modelPackage.set("net.djvk.fireflyPlaidConnector2.api.plaid.models")
    globalProperties.put("modelDocs", "false")
    globalProperties.put("apiDocs", "false")
    globalProperties.put("modelTests", "false")
    globalProperties.put("apiTests", "false")
    configOptions.put("groupId", "net.djvk")
    configOptions.put("packageName", "net.djvk.fireflyPlaidConnector2.api.plaid")
    configOptions.put("library", "jvm-ktor")
    configOptions.put("dateLibrary", "java8")
    configOptions.put("serializationLibrary", "jackson")
    configOptions.put("additionalModelTypeAnnotations", "@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)")
    configOptions.put("requestDateConverter", "toString")
    configOptions.put("typeMappings", "BigDecimal=Double")
    configOptions.put("omitGradleWrapper", "true")
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(Paths.get(generatePlaidClient.get().outputDir.get(), "src", "main", "kotlin"))
            // Local change: the management dashboard lives in its own tree, so rebasing on
            // upstream does not collide with it. See LOCAL_CHANGES.md.
            kotlin.srcDir("src/manage/kotlin")
        }
        test {
            kotlin.srcDir("src/manageTest/kotlin")
        }
    }
}

sourceSets {
    main {
        resources.srcDir("src/manage/resources")
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "17"
    }
    dependsOn(generatePlaidClient)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
