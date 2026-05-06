plugins {
    application
}

group = "dev.restate.kafka"
version = "0.1.0-SNAPSHOT"

val restateSdkVersion = "2.7.0"
val kafkaVersion = "3.8.0"
val jacksonVersion = "2.18.0"

dependencies {
    implementation(project(":lib"))
    implementation("dev.restate:sdk-java-http:$restateSdkVersion")
    annotationProcessor("dev.restate:sdk-api-gen:$restateSdkVersion")

    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    implementation("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass = "dev.restate.kafka.dedup.demo.DemoMain"
}

tasks.register<JavaExec>("runProducer") {
    group = "application"
    description = "Generate messages with intentional duplicates and publish to Kafka."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.restate.kafka.dedup.demo.ProducerMain"
    standardInput = System.`in`
}
