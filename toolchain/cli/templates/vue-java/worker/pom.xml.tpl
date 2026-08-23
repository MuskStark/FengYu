<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <!-- A self-contained worker build. It depends on the independently-versioned
       FengYu Plugin Worker SDK (2.1.0). In-repo builds resolve it from the local
       reactor install; external builds resolve it from GitHub Packages via
       .mvn/settings.xml (FENGYU_GITHUB_TOKEN with read:packages). The devkit
       (fengyu-plugin-devkit) is test-scoped so it never ships in the shaded JAR;
       PluginDevMain under src/test/java uses it to expose the worker over loopback
       TCP for IDE debugging. -->
  <groupId>{{javaPackage}}</groupId>
  <artifactId>{{javaClassPrefix}}-worker</artifactId>
  <version>1.0.0</version>
  <name>{{pluginName}} Worker</name>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <fengyu.plugin.sdk.version>2.1.0</fengyu.plugin.sdk.version>
    <gson.version>2.13.1</gson.version>
    <junit.version>5.10.2</junit.version>
    <maven.compiler.plugin.version>3.13.0</maven.compiler.plugin.version>
    <maven.surefire.plugin.version>3.2.5</maven.surefire.plugin.version>
    <maven.shade.plugin.version>3.5.3</maven.shade.plugin.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>fan.summer.fengyu.sdk</groupId>
      <artifactId>fengyu-plugin-sdk</artifactId>
      <version>${fengyu.plugin.sdk.version}</version>
    </dependency>
    <dependency>
      <groupId>fan.summer.fengyu.sdk</groupId>
      <artifactId>fengyu-plugin-devkit</artifactId>
      <version>${fengyu.plugin.sdk.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <finalName>{{javaClassPrefix}}-worker</finalName>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>${maven.compiler.plugin.version}</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>${maven.surefire.plugin.version}</version>
      </plugin>
      <!-- Produce a runnable fat JAR with the worker Main-Class on the manifest. -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>${maven.shade.plugin.version}</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>{{javaPackage}}.{{javaClassPrefix}}WorkerMain</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
