import com.github.jengelman.gradle.plugins.shadow.ShadowExtension
import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin
import com.github.jengelman.gradle.plugins.shadow.internal.JavaJarExec
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.robfletcher.compass.CompassExtension
import com.github.robfletcher.compass.CompassPlugin
import org.gradle.internal.impldep.org.junit.experimental.categories.Categories.CategoryFilter.exclude
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningPlugin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.js.translate.context.Namer.kotlin

buildscript {

    var kotlinVersion = "1.2.61"

    repositories {
        mavenLocal()
        jcenter()
        maven { setUrl("http://dl.bintray.com/robfletcher/gradle-plugins") }
        maven { setUrl("https://repo.spring.io/release") }
        maven { setUrl("https://repo.spring.io/snapshot") }
        mavenCentral()
    }
    dependencies {
        classpath("com.github.jengelman.gradle.plugins:shadow:2.0.3")
        classpath("com.github.robfletcher:compass-gradle-plugin:2.0.6")
        classpath("io.projectreactor.ipc:reactor-netty:0.7.4.RELEASE")
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
    java
    kotlin("jvm")
    application
}

apply {
    plugin<CompassPlugin>()
    plugin<ShadowPlugin>()
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
    archiveName = "lettuce-home-all.jar"
}

application {
    mainClassName = "io.lettuce.Application"
}

tasks.withType<JavaJarExec>()

configure<CompassExtension> {
    sassDir = file("$projectDir/src/main/sass")
    cssDir = file("$buildDir/resources/main/static/assets/css")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven { setUrl("http://repo.spring.io/release") }
    maven { setUrl("http://repo.spring.io/milestone") }
    maven { setUrl("https://repo.spring.io/snapshot") }
}

dependencies {

    var kotlinVersion = "1.2.61"

    compile(kotlin("stdlib-jre8", kotlinVersion))
    compile(kotlin("reflect", kotlinVersion))

    compile("org.springframework:spring-core:5.1.0.RELEASE")

    compile("io.projectreactor.ipc:reactor-netty:0.7.9.RELEASE") {
        exclude(group = "io.netty", module = "netty-transport-native-epoll")
    }
    compile("io.projectreactor:reactor-core:3.2.0.RELEASE")

    compile("io.lettuce:lettuce-core:5.1.0.RELEASE") {
        exclude(group = "io.netty")
    }

    compile("org.thymeleaf:thymeleaf:3.0.9.RELEASE")
    compile("com.fasterxml.jackson.core:jackson-databind:2.9.7")
    compile("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.7")
    compile("org.slf4j:slf4j-api:1.7.25")
    runtime("commons-logging:commons-logging:1.2")
    runtime("ch.qos.logback:logback-classic:1.1.7")
    compileOnly("org.projectlombok:lombok:1.18.0")
}

val processResources = tasks.getByName("processResources")
val compassCompile = tasks.getByName("compassCompile")
processResources.dependsOn(compassCompile)
