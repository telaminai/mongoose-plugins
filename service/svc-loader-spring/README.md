# svc-loader-spring

Loads Fluxtion event-processor graphs from external Spring XML application contexts at runtime, then registers each compiled (or interpreted) processor with the running Mongoose server. Built on `FluxtionSpring` / `FluxtionSpringInterpreter` (in `fluxtion-builder`'s `extern.spring` package).

If `svc-loader-yaml` is the entry point for users who think in YAML, this is the entry point for those who already use Spring XML to describe object graphs.

## Capabilities

| Capability | Admin command |
|---|---|
| Compile a Spring XML context into a generated processor | `springLoader.compileProcessor <xml-file> [<group-name>]` |
| Interpret a Spring XML context (no codegen) | `springLoader.interpretProcessor <xml-file> [<group-name>]` |
| Reload an already-loaded processor (compile path) | `springLoader.reloadCompileProcessor <xml-file>` |
| Reload an already-loaded processor (interpret path) | `springLoader.reloadInterpretProcessor <xml-file>` |
| List currently-loaded processors | `springLoader.listLoaded` |

You can also configure a static list of Spring XML files via the `loadAtStartup` property.

## Maven coordinates

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-loader-spring</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

Built against `spring-context` 6.2.1 (Spring Framework 6, Jakarta EE namespace).

## Usage

```yaml
services:
  - name: springEventHandlerLoader
    instance: !!com.fluxtion.dataflow.serverplugin.loader.spring.SpringEventHandlerLoader
      addEventAuditor: false
      loadAtStartup:
        - springFile: ./config/pricing-beans.xml
          group: pricing-group
          compile: true
```

A minimal Spring XML graph:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans
                           https://www.springframework.org/schema/beans/spring-beans.xsd">

    <bean id="ticker" class="com.example.TickerNode">
        <constructor-arg value="AAPL"/>
    </bean>

    <bean id="average" class="com.example.MovingAverageNode">
        <constructor-arg ref="ticker"/>
        <constructor-arg value="30"/>
    </bean>
</beans>
```

The bean graph becomes the Fluxtion node graph (one bean = one node, `ref` arguments = wiring). The `FluxtionSpring` builder walks the context and produces an `EventProcessorConfig`.

## Notes

- Reload commands are useful for iterative dev: edit the XML, hit `springLoader.reloadCompileProcessor`, observe the new processor without restarting the server.
- `addEventAuditor=true` wraps every event in the Fluxtion auditor — great for testing, expensive for production.
- Compiled path generates Java source at runtime and requires `javac` on the classpath. Interpreted path reflects only.

## Tests

No unit tests in this module — Spring context loading is integration-test territory. End-to-end coverage lives in the `mongoose-complete-example` repo where the loader is exercised against real XML.

## Related

- [`svc-loader-yaml`](../svc-loader-yaml/) — same idea, YAML config instead of Spring XML.
- [`svc-admin-telnet`](../svc-admin-telnet/) / [`svc-admin-rest`](../svc-admin-rest/) — admin endpoints that surface the loader's commands.
