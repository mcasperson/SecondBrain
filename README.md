# SecondBrain

Imagine a world where you can have a conversation with your data. SecondBrain is a framework that allows you to do just
that by linking plain text prompts with external data sources, commonly referred to as Retrieval Augmented Generation (
RAG). The resulting context is then presented an LLM to provide the information it needs to answer your questions.

This means you can ask a question like:

> Highlight the important changes made since October 1 2024 in the GitHub repository "SecondBrain" owned by "mcasperson"

and get a meaningful response against real-time data.

![Screenshot](screenshot.png)

## Example Usages

An example usage of SecondBrain is a prompt like this:

```
Summarize 7 days worth of messages from the #announcements channel in Slack
```

This prompt is handled like this:

1. The prompt is passed to Ollama which selects the `secondbrain.tools.SlackChannel` tool. This is commonly referred to
   as [tool or function calling](https://www.llama.com/docs/model-cards-and-prompt-formats/llama3_2/#-tool-calling-(1b/3b)-).
2. Ollama also extracts the channel name `#announcements` and days `7` from the prompt as an argument.
3. The `secondbrain.tools.SlackChannel` tool is called with the argument `#announcements` and `7`.
4. The tool uses the Slack API to find messages from the last `7` days in the `#announcements` channel.
5. The messages are placed in the context of the original prompt and passed back to Ollama.
6. Ollama answers the prompt with the messages context and returns the result to the user.

## Technologies used

SecondBrain makes heavy use of Jakarta EE and MicroProfile, executed in a Docker image using Payara Micro.

## Running the application

Secondbrain is distributed as a Docker image and run in parallel with Ollama using Docker Compose:

1. `git clone https://github.com/mcasperson/SecondBrain.git` to clone the repository
2. `cd SecondBrain` to enter the project directory
3. `docker compose up` to start the Docker Compose stack
4. `docker exec secondbrain-ollama-1 ollama pull llama3.2` to pull the `llama3.2` LLM
5. Create
   a [GitHub access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)
6. Open https://localhost:8181, paste in your access token, and click `Submit` to answer the default query

## Project Structure

The project is split into modules:

* `secondbrain-core` which contains shared utilities and interfaces used by all other modules.
* `secondbrain-service` which orchestrates the function calling with the LLM.
* `secondbrain-tools` which contains the tools that interact with external data sources.
* `secondbrain-web` which is a web interface for interacting with the service.
* `secondbrain-cli` which is a CLI tool for interacting with the service.

# Configuration

SecondBrain is configured via MicroProfile Config. Note that MicroProfile allows these configuration values to be set
via [a number of different locations](https://smallrye.io/smallrye-config/Main/config/getting-started/), including
environment variables, system properties, and configuration files:

* `sb.slack.clientid` - The Slack client ID
* `sb.slack.clientsecret` - The Slack client secret
* `sb.zendesk.accesstoken` - The ZenDesk token
* `sb.zendesk.user` - The ZenDesk user
* `sb.zendesk.url` - The ZenDesk url
* `sb.ollama.url` - The URL of the Ollama service (defaults to http://localhost:11434)
* `sb.ollama.model` - The model to use in Ollama (defaults to `llama3.2`)
* `sb.ollama.contentlength` - The content window length to use in Ollama (defaults to `7000 * 4`, where each token is
  assumed to be 4 characters)
* `sb.encryption.password` - The password to use for encrypting sensitive data stored by web clients (defaults to
  `12345678`)
* `sb.tools.debug` - Whether to log debug information about the tool in the response (defaults to `false`)

## Supported LLMs

SecondBrain is built around [Ollama](https://ollama.com/), which a is a local service exposing a huge selection of LLMs.
This ensures that your data and prompts are kept private and secure.

## Testing

* Install Ollama locally
* Pull the `llama3.2` model with the command `ollama pull llama3.2`
* Build and install all the modules with command `mvn clean install`
* Start Payara Micro with the command `cd web; mvn package; mvn payara-micro:start`
* Create
  a [GitHub access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)
* Open [https://localhost:8181/index.html](https://localhost:8181/index.html) in a browser, paste in the access token,
  and run the default query

## New Tools

See the `secondbrain.tools.HelloWorld` tool for an example of how to create a new tool.