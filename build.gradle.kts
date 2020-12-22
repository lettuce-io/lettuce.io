import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.robfletcher.compass.CompassExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.concurrent.TimeUnit

buildscript {

    var kotlinVersion = "1.2.61"

    repositories {
        mavenLocal()
        maven { setUrl("http://dl.bintray.com/robfletcher/gradle-plugins") }
        mavenCentral()
        jcenter()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${kotlinVersion}")
        classpath("org.jetbrains.kotlin:kotlin-allopen:${kotlinVersion}")
    }
}

configurations.all {
    resolutionStrategy {
        force("rubygems:rb-inotify:0.9.10")
    }
}

plugins {
    id("com.github.anbuck.compass") version "2.0.7"
    java
    kotlin("jvm") version "1.3.72"
    application
    id("com.github.johnrengelman.shadow") version "5.2.0"
}

configure<ApplicationPluginConvention> {
    mainClassName = "io.lettuce.Application"
}

group = "io.lettuce"
version = "1.0.0.BUILD-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }
}

tasks.withType<ShadowJar> {
   archiveFileName.set("lettuce-home-all.jar")
}

configure<CompassExtension> {
    sassDir = file("$projectDir/src/main/sass")
    cssDir = file("$buildDir/resources/main/static/assets/css")
}

repositories {
    mavenLocal()
    mavenCentral()
    jcenter()
}

dependencies {

    var kotlinVersion = "1.3.72"

    compile(kotlin("stdlib-jdk8", kotlinVersion))
    compile(kotlin("reflect", kotlinVersion))

    compile("org.springframework:spring-core:5.1.0.RELEASE")

    compile("io.projectreactor.ipc:reactor-netty:0.7.9.RELEASE") {
        exclude(group = "io.netty", module = "netty-transport-native-epoll")
    }
    compile("io.projectreactor:reactor-core:3.2.0.RELEASE")

    compile("io.lettuce:lettuce-core:5.2.1.RELEASE") {
        exclude(group = "io.netty")
    }

    compile("org.thymeleaf:thymeleaf:3.0.9.RELEASE")
    compile("com.fasterxml.jackson.core:jackson-databind:2.10.1")
    compile("com.fasterxml.jackson.module:jackson-module-kotlin:2.10.1")
    compile("org.slf4j:slf4j-api:1.7.25")
    runtime("commons-logging:commons-logging:1.2")
    runtime("ch.qos.logback:logback-classic:1.1.7")
    compileOnly("org.projectlombok:lombok:1.18.0")
}

val processResources = tasks.getByName("processResources")
val compassCompile = tasks.getByName("compassCompile")
processResources.dependsOn(compassCompile)
