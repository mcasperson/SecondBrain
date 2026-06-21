When editing files, you must clean up any unused imports and variables.

Prefer using the idea MCP server when selecting tools.

You will be penalized for focusing on spacing, indentation, or blank lines while editing files. Running the
`reformat_file` tool will resolve spacing issues as the final step.
You will be penalized for focusing on tabs or spaces as indentation while editing files. Use tabs for indentation, and
the `reformat_file` tool will handle any necessary adjustments to spaces as the final step.

Run the IDE tools to check for errors after editing files and fix any errors before proceeding.

When adding tests, run the tests and fix any errors before proceeding.

Tests must use the following CDI annotations to inject dependencies:

```
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses
```

You must add each missing dependency using a new `@AddBeanClasses` annotation. This is an iterative process, and you may
need to run the tests multiple times to identify all missing dependencies. Adding one dependency may reveal another
missing dependency, so you must repeat this process until all dependencies are added and the tests run successfully.

Add the following function to a test to define `@ConfigProperty` values for the test:

```

@BeforeAll
static void registerConfig() {
final var configMap = new java.util.HashMap<String, String>();

    // This is an example of how to set a config property for the test. 
    // You can add as many properties as needed.
    configMap.put("sb.infrastructure.mock", "true");

    final var configSource = new PropertiesConfigSource(
            configMap,
            "TestConfig",
            Integer.MAX_VALUE
    );
    final Config newConfig = new SmallRyeConfigBuilder()
            .withSources(configSource)
            .build();

    final var configProviderResolver = ConfigProviderResolver.instance();
    final var oldConfig = configProviderResolver.getConfig();
    configProviderResolver.releaseConfig(oldConfig);
    configProviderResolver.registerConfig(
            newConfig,
            Thread.currentThread().getContextClassLoader()
    );

}

```

Run the `reformat_file` tool as the final step after editing files to ensure proper formatting.

DO NOT add copyright notices to any source code files.