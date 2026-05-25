// Test fixture for the /api/source classpath-fallback path. This stub
// stands in for a Fluxtion-generated processor whose .java rides into the
// jar as a classpath resource (see FluxtionCompilerConfig
// .copySourceToResourcesDirectory, default-on in fluxtion 1.0.2+).
//
// NOT compiled — the file lives under src/test/resources so it's available
// via ClassLoader.getResourceAsStream("com/example/fakegen/StubProcessor.java")
// without becoming an actual class on the test classpath.
package com.example.fakegen;

public class StubProcessor {
    public static final String MARKER = "stub-processor-marker";
}
