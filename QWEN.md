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

You must add each missing dependency using a new `@AddBeanClasses` annotation. This is an iterative process, and you may
need to run the tests multiple times to identify all missing dependencies. Adding one dependency may reveal another
missing dependency, so you must repeat this process until all dependencies are added and the tests run successfully.

Use `TestConfigUtil.registerConfig()` to register any required `ConfigProperty` values in your tests.

Run the `reformat_file` tool as the final step after editing files to ensure proper formatting.

DO NOT add copyright notices to any source code files.