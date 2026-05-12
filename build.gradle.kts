import com.vanniktech.maven.publish.SonatypeHost
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `java-library`
    id("io.spring.dependency-management") version "1.1.6"
    id("com.vanniktech.maven.publish") version "0.30.0"
    signing
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${property("springBootVersion")}")
    }
}

dependencies {
    // Spring Boot — compileOnly because the host application provides these
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("org.springframework.boot:spring-boot-starter-data-jpa")
    compileOnly("org.springframework.boot:spring-boot-starter-websocket")
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")
    compileOnly("org.springframework.boot:spring-boot-starter-validation")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure-processor")

    // Annotation processor for configuration metadata
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")

    // Library deps shipped with the starter
    api("org.liquibase:liquibase-core")
    api("com.github.ben-manes.caffeine:caffeine")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("com.h2database:h2")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Javadoc> {
    options {
        encoding = "UTF-8"
        (this as StandardJavadocDocletOptions).apply {
            addStringOption("Xdoclint:none", "-quiet")
            charSet("UTF-8")
            docEncoding("UTF-8")
            locale = "en_US"
        }
    }
    isFailOnError = false
}

tasks.withType<Test> {
    useJUnitPlatform()
}

mavenPublishing {
    // Publish to the new Sonatype Central Portal (https://central.sonatype.com)
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)

    // Sign all publications
    signAllPublications()

    coordinates(
        groupId = project.group.toString(),
        artifactId = project.name,
        version = project.version.toString()
    )

    configure(
        JavaLibrary(
            javadocJar = JavadocJar.Javadoc(),
            sourcesJar = true,
        )
    )

    // POM metadata (name, description, url, license, developers, scm,
    // issueManagement) is read from POM_* keys in gradle.properties by the
    // com.vanniktech.maven.publish plugin. Do NOT duplicate them in a pom {}
    // block here — that produces duplicated entries in the generated POM.

    pom {
        // issueManagement is the one section the plugin does NOT auto-derive
        // from POM_ISSUE_* keys, so we wire it explicitly.
        issueManagement {
            system.set(providers.gradleProperty("POM_ISSUE_SYSTEM"))
            url.set(providers.gradleProperty("POM_ISSUE_URL"))
        }
    }
}
