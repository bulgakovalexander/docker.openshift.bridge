import com.bmuschko.gradle.docker.tasks.image.Dockerfile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    var kotlinVersion: String by extra
    kotlinVersion = "1.3.21"

    var springBootVersion: String by extra
    springBootVersion = "2.1.9.RELEASE"

    repositories {
        mavenCentral()
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }
    dependencies {
        classpath("org.springframework.boot:spring-boot-gradle-plugin:$springBootVersion")
    }
}

plugins {
    val kotlinVersion: String by extra
    id("org.jetbrains.kotlin.jvm") version kotlinVersion
    kotlin("plugin.spring") version kotlinVersion
    kotlin("kapt") version kotlinVersion
    id("io.spring.dependency-management") version "1.0.6.RELEASE"
    val springBootVersion: String by extra
    id("org.springframework.boot") version springBootVersion
    id("com.bmuschko.docker-spring-boot-application") version "5.2.0"
//    id("com.palantir.docker") version "0.22.1"
}

group = "hyperledger.fabric.openshift.bridge"
version = "0.1-SNAPSHOT"

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

repositories.addAll(buildscript.repositories)

dependencyManagement {
    dependencies {
        val springBootVersion: String by rootProject.extra
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:Greenwich.RELEASE")
        }
    }
}

dependencies {

    kapt("org.springframework.boot:spring-boot-configuration-processor")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor")

    implementation("io.fabric8:kubernetes-client:4.6.0")
    implementation("io.fabric8:kubernetes-model:4.6.0")
    implementation("io.fabric8:openshift-client:4.6.0")
    implementation("com.github.docker-java:docker-java:3.1.5")

    implementation("com.squareup.okhttp3:okhttp:3.14.3")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.github.microutils:kotlin-logging:1.6.22")

    implementation("org.springframework.boot:spring-boot-starter-undertow")
    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude("org.springframework.boot", "spring-boot-starter-tomcat")
    }

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.github.microutils:kotlin-logging:1.7.6")
    implementation("org.apache.commons:commons-compress:1.19")

}

sourceSets {
    val main: SourceSet by this
    create("springConfig") {
        runtimeClasspath = main.runtimeClasspath
        resources.srcDir("config")
    }
}

kapt {
    includeCompileClasspath = false
    showProcessorTimings = true
}

docker {
    springBootApplication {
        tag.set("${project.group}:${project.version}")
    }
}

tasks.withType(Dockerfile::class.java) {
    //    project.file("config/application.yml").copyTo(project.file("$buildDir/docker/application.yml"), true)
//    addFile("application.yml", "/config/application.yml")
}

