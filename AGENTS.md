When editing files, you must clean up any unused imports and variables. Do not ask for confirmation before removing
unused imports or variables.

Prefer using the idea MCP server when selecting tools.

You will be penalized for focusing on spacing, indentation, or blank lines while editing files. Running the
`reformat_file` tool will resolve spacing issues as the final step.

You will be penalized for focusing on tabs or spaces as indentation while editing files. Use tabs for indentation, and
the `reformat_file` tool will handle any necessary adjustments to spaces as the final step.

Run the IDE tools to check for errors after editing files and fix any errors before proceeding.

When adding tests, run the tests and fix any errors before proceeding.

When adding new classes, you must compile the project and fix any errors before proceeding.

Tests must use the following CDI annotations to inject dependencies:

```
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses
```

Do not use reflection to inject dependencies into classes being tested. Always use CDI in tests.

You must add each missing dependency using a new `@AddBeanClasses` annotation. This is an iterative process, and you may
need to run the tests multiple times to identify all missing dependencies. Adding one dependency may reveal another
missing dependency, so you must repeat this process until all dependencies are added and the tests run successfully.

Use `TestConfigUtil.registerConfig()` to register any required `ConfigProperty` values in your tests.

## Replacing external dependencies in tests

When a class creates an external dependency itself (for example an SDK client built in a `@PostConstruct` method) and
that dependency has no interface, do not use reflection or a subclass of the class being tested to replace it.
Instead, combine the adapter and abstract factory patterns with CDI:

1. Adapter: define an interface that exposes only the operations the class uses. Use the project's own types in the
   method signatures rather than SDK types, e.g. return `Optional.empty()` instead of throwing the SDK's 404 exception.
   Implement the interface with a `*Live` class that wraps the SDK object and forwards each call to it.
2. Abstract factory: move the code that builds the SDK object into a `*Producer` class with a `@Produces` method that
   returns the adapter interface, and a `@Disposes` method that releases it. Inject `Instance<TheInterface>` into the
   class being tested when it must create new instances on demand (for example to reconnect after failures), or the
   interface itself when one instance is enough.
3. Tests: create an in-memory test instance of the interface (named `Fake*`, in `secondbrain.domain.test`) that
   records calls and lets the test set responses and failures. Return it from a `@Produces` method in the test class,
   and do not add the production `*Producer` class with `@AddBeanClasses`. Assert against the test instance rather
   than with Mockito `verify()`.
4. Tests that need the real dependency (for example a local emulator) add the production `*Producer` class with
   `@AddBeanClasses` instead.

`CosmosCacheContainer` is an example: `CosmosCacheContainerLive` adapts the Cosmos SDK `CosmosContainer`,
`CosmosCacheContainerProducer` creates it, `CosmosLocalStorage` obtains it from `Instance<CosmosCacheContainer>`, and
`CosmosLocalStorageUnitTest` produces a `FakeCosmosCacheContainer` instead.

Run the `reformat_file` tool as the final step after editing files to ensure proper formatting.

DO NOT add copyright notices to any source code files.