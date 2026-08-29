<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.telamin</groupId>
        <artifactId>mongoose-plugins</artifactId>
        <version>1.0.40</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>svc-admin-web</artifactId>
    <name>telamin :: mongoose-plugins :: svc-admin-web</name>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.javalin</groupId>
            <artifactId>javalin</artifactId>
            <version>6.3.0</version>
        </dependency>
        <!-- Javalin auto-uses Jackson for ctx.json(...) / ctx.bodyAsClass(...) when present. -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.18.3</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <!-- Keep unit tests out of the developer's real ~/.mongoose/servers:
                         WebAdminService publishes its UP-MNG-01 registry file there by default. -->
                    <systemPropertyVariables>
                        <mongoose.servers.dir>${project.build.directory}/servers</mongoose.servers.dir>
                    </systemPropertyVariables>
                </configuration>
            </plugin>
        </plugins>
    </build>

</project>
