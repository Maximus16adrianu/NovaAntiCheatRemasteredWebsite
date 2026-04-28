plugins {
    java
    application
}

group = "me.cerial.nova"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("org.slf4j:slf4j-api:1.7.32")
    implementation("org.slf4j:slf4j-simple:1.7.5")
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
}

application {
    mainClass.set("me.cerial.nova.cloudcombat.CloudCombatServer")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

tasks {
    jar {
        archiveFileName.set("Nova-CombatCloud.jar")
        manifest {
            attributes("Main-Class" to application.mainClass.get())
        }
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}
