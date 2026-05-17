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

```yaml
services:
  - name: springLoader
    instance: !!com.fluxtion.dataflow.serverplugin.svc.loader.spring.SpringProcessorLoaderService
      processorsDir: ./processors
```

Plus a Spring XML file:

```xml
<beans xmlns="http://www.springframework.org/schema/beans">
  <bean id="tradeEnricher"
        class="com.example.TradeEnricher">
    <property name="threshold" value="100"/>
  </bean>
</beans>
```

At runtime, drop the XML in `processorsDir` and invoke:

```
admin > loader.springLoader.load trade-enricher.xml
```

## Operational notes

- Spring's full bean-definition parser is on the classpath; you get the usual `<bean>`, `<property>`, `<ref>`, profile, and import semantics.
- The compiled topology runs as a regular Fluxtion processor — no Spring container at runtime.

## Examples

A dedicated Spring-XML loader example is on the [Examples](../examples.md) roadmap. For the runtime shape, see the YAML loader example — the loaded-processor side is identical:

- **[getting-started/five-minute-yaml-tutorial](https://github.com/telaminai/mongoose-examples/tree/main/getting-started/five-minute-yaml-tutorial)**

## Source

[`mongoose-plugins/service/svc-loader-spring`](https://github.com/telaminai/mongoose-plugins/tree/main/service/svc-loader-spring)
