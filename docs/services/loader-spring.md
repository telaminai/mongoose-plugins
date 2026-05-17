# svc-loader-spring

<span class="plugin-tags">
  <span class="plugin-tag service">service</span>
</span>

Load and reload processors at runtime from Spring XML. Same surface as `svc-loader-yaml` but with Spring XML as source-of-truth.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-loader-spring</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## When to use

- You're integrating with existing Spring-XML applications and want to reuse their bean definitions.
- You need an "external authoring" surface — non-Java tooling (GUIs, LLMs, regulated-industry tooling) can emit XML that Fluxtion compiles into a deterministic processor.

The Spring XML format positions Fluxtion as a compiler that accepts arbitrary authoring tools — see the [Fluxtion external-authoring pattern](https://github.com/telaminai/fluxtion) for the broader context.

## Sample

Register the loader as a service and point it at one or more Spring XML
bean graphs to instantiate at boot:

```yaml
services:
  - name: springLoaderService
    service: !!com.fluxtion.dataflow.serverplugin.loader.spring.SpringEventHandlerLoader
      addEventAuditor: false
      loadAtStartup:
        - { springFile: "config/pricing-beans.xml", compile: true }
```

Or, programmatically from Java:

```java
SpringEventHandlerLoader.EventSpringFile load = new SpringEventHandlerLoader.EventSpringFile();
load.setSpringFile("config/pricing-beans.xml");
load.setCompile(true);

SpringEventHandlerLoader loader = new SpringEventHandlerLoader();
loader.setLoadAtStartup(Set.of(load));

MongooseServerConfig.builder()
    .addService(ServiceConfig.<SpringEventHandlerLoader>builder()
        .service(loader)
        .serviceClass(SpringEventHandlerLoader.class)
        .name("springLoaderService")
        .build())
    .build();
```

The processor XML uses plain Spring `<bean>` definitions — each bean becomes
a node in the generated event-processor graph, `<property>` values flow
through standard bean-property setters, `<ref>` wires inputs:

```xml
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans
                           https://www.springframework.org/schema/beans/spring-beans.xsd">
  <bean id="tradeEnricher" class="com.example.TradeEnricher">
    <property name="threshold" value="100"/>
  </bean>
</beans>
```

`TradeEnricher` is a plain `ObjectEventHandlerNode` — no `start()` override
or explicit `subscribeToNamedFeed(...)` call required. Under `broadcast=true`
feeds (the catalogue's default), the dispatcher wires the dynamically-loaded
processor through the same subscription path as a statically-registered one.

When `svc-admin-rest` or `svc-admin-telnet` is on the classpath, the loader
also registers five admin commands:

- `springLoader.compileProcessor <xmlFile> [group]` — compile and add a Spring topology
- `springLoader.interpretProcessor <xmlFile> [group]` — same, via the Fluxtion interpreter
- `springLoader.reloadCompileProcessor <group/xmlFile>` — stop and reload (compile path)
- `springLoader.reloadInterpretProcessor <group/xmlFile>` — stop and reload (interpret path)
- `springLoader.listLoaded` — list currently-loaded processors

## Operational notes

- Spring's full bean-definition parser is on the classpath; you get the usual `<bean>`, `<property>`, `<ref>`, profile, and import semantics.
- The compiled topology runs as a regular Fluxtion processor — **no Spring container at runtime**. Spring is used at compile time only, to parse the bean graph.
- Built against `spring-context` 6.2.1 (Spring Framework 6 / Jakarta EE namespace). `spring-context` is declared `provided` in the plugin pom — your application brings its own version.

## Examples

- **[plugins/spring-service-loader-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/spring-service-loader-example)** — `SpringEventHandlerLoader` registered as a service with `loadAtStartup`; instantiates a handler from `log-processor.xml`, feeds it events from an in-memory source, prints them through the Spring-injected prefix.

## Gotchas

- **`mongoose` >= 1.0.9 required.** Earlier versions miss event-processor
  agents that the loader registers during its own `start()` hook — the
  processor would init but never receive events. Fixed by a late-start pass
  in `MongooseServer.start()`.

## Source

[`mongoose-plugins/service/svc-loader-spring`](https://github.com/telaminai/mongoose-plugins/tree/main/service/svc-loader-spring)
