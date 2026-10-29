# Lesson 1: Start a Spring Boot application

## 1. What are we building today?

We are creating the starting point for the backend of the collaborative workspace. By the end of this lesson, a Java program starts an embedded web server. There are no document endpoints yet; that is the next lesson.

**Goal:** build and start the application successfully, understand each file, and stop it safely.

**Not needed today:** PostgreSQL credentials, Redis, Docker containers or the frontend. We will use your local PostgreSQL when the database lesson arrives.

## 2. The basic idea

Imagine a restaurant:

- **Java** is the language we use to write the restaurant's instructions.
- **Maven** is the tool that gathers the ingredients (libraries) and builds the application from those instructions.
- **Spring Boot** helps start and configure the application using those libraries.
- **Tomcat** is the embedded web server that listens for incoming HTTP requests, like a front desk waiting for customers.

These are analogies, not exact definitions. In technical terms, Java is a programming language; Maven is a build and dependency-management tool; Spring Boot simplifies configuring and running Spring applications; and Tomcat handles HTTP connections for this web application.

The Java Development Kit (**JDK**) includes tools such as the Java compiler. The Java Virtual Machine (**JVM**) runs Java bytecode. Your computer currently has Java 24.0.2 and Maven 3.9.8. We use Java 24 for this lesson; [Spring Boot's requirements](https://docs.spring.io/spring-boot/system-requirements.html) list the supported versions.

## 3. What files were created?

```text
collaborate_workspace/
├── .gitignore
└── backend/
    ├── pom.xml
    └── src/main/java/com/example/collaborativeworkspace/
        └── CollaborativeWorkspaceApplication.java
```

After building, Maven also creates `backend/target/`. It contains generated files, including a runnable JAR. We do not edit those generated files directly.

## 4. File 1: the Maven configuration

Path: `backend/pom.xml`

The **POM** (Project Object Model) is Maven's project configuration. This is the complete file:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.2</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>collaborative-workspace</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>collaborative-workspace</name>
    <description>Backend for a collaborative workspace</description>

    <properties>
        <java.version>24</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

Read it from the inside out:

1. `<parent>` selects Spring Boot 4.1.2's Maven defaults and managed dependency versions. It does not add the web library by itself. `<relativePath/>` tells Maven not to look for a parent POM in the parent folder.
2. `groupId`, `artifactId`, and `version` identify **our** project. `SNAPSHOT` means this is a development version.
3. `<java.version>24</java.version>` tells the build which Java release to compile for; Maven must run with a compatible JDK. It matches the installed version we checked.
4. `spring-boot-starter-webmvc` adds libraries for a Spring MVC web application, including embedded Tomcat. The parent manages its version, so we do not repeat it here. [Spring's first application guide](https://docs.spring.io/spring-boot/tutorial/first-application/)
5. `spring-boot-maven-plugin` adds build and run support for Spring Boot, including packaging an executable JAR.

**What is a dependency?** A reusable library our project needs. Maven downloads dependencies into its local cache on the first build; this is why the first build can take longer and needs network access.

## 5. File 2: the application entry point

Path: `backend/src/main/java/com/example/collaborativeworkspace/CollaborativeWorkspaceApplication.java`

```java
package com.example.collaborativeworkspace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CollaborativeWorkspaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollaborativeWorkspaceApplication.class, args);
    }
}
```

**Line by line:**

- `package ...;` places our class in a namespace. Its folder structure mirrors the package name.
- `import ...;` lets us refer to Spring's classes by their short names.
- `@SpringBootApplication` is a Java **annotation**: metadata Spring reads to configure the application. It combines configuration, automatic configuration and component scanning. For example, the web dependency helps Boot choose an embedded server configuration.
- `public class ...` declares our application class.
- `public static void main(String[] args)` is the entry point Java calls when the program starts. `args` contains command-line arguments.
- `SpringApplication.run(...)` starts Spring Boot using this application class and passes it those arguments. Spring creates its application context and starts the embedded web server.

An **application context** is Spring's container for managing configured objects, called **beans**. We will create and use our own beans in a later lesson.

## 6. File 3: `.gitignore`

The root `.gitignore` tells Git to ignore generated Maven output, common editor files and local `.env` files if this folder is later initialized as a Git repository. Ignoring files does not encrypt secrets or remove files already tracked by Git. We have not put database credentials in the project.

## 7. Build and run on your computer

Open PowerShell and run:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
mvn -B package
```

`mvn` launches Maven, `-B` uses batch output, and `package` compiles the source and packages it. Expected result near the end:

```text
[INFO] BUILD SUCCESS
```

There are currently no automated tests, so Maven reports `No tests to run`; a successful build does not mean application behavior has been fully tested.

Start the application:

```powershell
mvn spring-boot:run
```

Wait for a line similar to:

```text
Tomcat started on port 8080 (http)
Started CollaborativeWorkspaceApplication
```

The process continues running because the server waits for requests. To stop it, press **Ctrl+C** in that PowerShell window.

Open `http://localhost:8080/` in a browser while it is running. A **404 Not Found** response is expected: the server is listening, but we have not created a page or endpoint for `/`. A 404 here is evidence that an HTTP server responded, not proof that a document API exists.

Alternatively, after `mvn -B package`, you can run the built JAR:

```powershell
java -jar target/collaborative-workspace-0.0.1-SNAPSHOT.jar
```

Run only one of the two startup commands at a time because both normally use port 8080. For a temporary different port, use `java -jar target/collaborative-workspace-0.0.1-SNAPSHOT.jar --server.port=8081`.

## 8. What we verified

On this computer, `mvn -B package` completed with `BUILD SUCCESS`. The packaged application started using Java 24.0.2 and Spring Boot 4.1.2. For the check, we used `--server.port=0`, which lets the operating system select a free port; the startup log reported the selected port. An HTTP request to `/` returned the expected 404. The verification process was then stopped. No automated tests have been added yet.

## 9. Common problems

| Problem | Meaning and first check |
|---|---|
| `mvn` or `java` is not recognized | Open a fresh PowerShell window and check `mvn -version` and `java -version`; the executable may not be on PATH. |
| Port 8080 is already in use | Another program is listening there. Stop only a program you own, or start this app on a different port using the command above. |
| Maven cannot download dependencies | Check network access and the exact error; dependencies must be available in Maven's cache or downloadable. |
| Build says the Java release is unsupported | Compare `mvn -version` with `<java.version>` in the POM; Maven may be using a different JDK from the one you expected. |
| Java prints Maven library warnings | Your installed Maven emitted restricted/native-access and deprecation warnings during our successful build. They are warnings, not evidence that this build failed; read any new error separately. |
| Browser shows 404 | Expected in this lesson because no route has been implemented. Confirm the application actually started and you used its port. |

## 10. Interview questions

**Q: What is Spring Boot?**

It is a framework for building Spring applications with conventions and automatic configuration that reduce setup. We still choose dependencies and application behavior explicitly.

**Q: What does Maven do?**

It resolves dependencies, compiles source code and runs the configured build lifecycle, such as packaging and tests.

**Q: Why does the application keep running after `main` starts it?**

The embedded web server and application infrastructure stay active to receive requests. Stopping the process shuts the application down.

**Q: What is an embedded server?**

A server library packaged with and started by the application, so we can run the JAR without separately deploying it to a standalone Tomcat installation.

**Q: Does starting the server mean our application is ready for users?**

No. We have only verified startup and an expected 404. We have not built document features, authentication, persistence or production controls.

## 11. Small exercise

Run the app yourself, find the line showing the port, open `/` in the browser and stop the app. Then explain in your own words why seeing a 404 is expected today. Next lesson: create an actual HTTP endpoint and learn how a controller responds to a request.
