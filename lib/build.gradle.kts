plugins {
    `java-library`
}

group = "dev.restate.kafka"
version = "0.1.0-SNAPSHOT"

val restateSdkVersion = "2.7.0"

dependencies {
    api("dev.restate:sdk-api:$restateSdkVersion")
    implementation("dev.restate:client:$restateSdkVersion")
    implementation("dev.restate:common:$restateSdkVersion")
    annotationProcessor("dev.restate:sdk-api-gen:$restateSdkVersion")

    testImplementation("dev.restate:sdk-java-http:$restateSdkVersion")
    testImplementation("dev.restate:sdk-testing:$restateSdkVersion")
    testAnnotationProcessor("dev.restate:sdk-api-gen:$restateSdkVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
