# SecondBrain

Imagine a world where you can have a conversation with your data. SecondBrain is a framework that allows you to do just that by linking plain text prompts with external data sources. The result is then presented an LLM to provide the information it needs to answer your questions. 

This means you can ask a question like:

> Highlight the important changes made in the last 7 days in the GitHub repository "SecondBrain" owned by "mcasperson"

and get a meaningful response.

## Technologies used

SecondBrain makes heavy use of Jakarta EE and MicroProfile, executed in a Docker image using Payara Micro.

## Running the application

Secondbrain is distributed as a Docker image and run in parallel with Ollama using Docker Compose:

1. `git clone https://github.com/mcasperson/SecondBrain.git` to clone the repository
2. `cd SecondBrain` to enter the project directory
3. `docker compose up` to start the Docker Compose stack
4. `docker exec secondbrain-ollama-1 ollama pull llama3.2` to pull the `llama3.2` LLM
5. Create a [GitHub access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)
6. Open https://localhost:8081, paste in your access token, and click `Submit` to answer the default query

## How it works

SecondBrain is a platform that allows you to have a conversation with your data.

It works like this:

1. You enter a plain text prompt about your data, such as GitHub commits or Slack messages.
2. The prompt is passed to an LLM along with definitions of "tools" that can be used to interact with the data.
3. The LLM selects the correct tool to collect the required data.
4. The tool then collects the data, places it in the context of an LLM prompt, and passes the prompt and context to the LLM.
5. The LLM response is returned to the user.

The power in this platform is the ability to easily create new tools that interact with data sources. With a few simple
HTTP calls you can create a new tool that link into almost any external data source.

## Project Structure

The project is split into modules:

* `secondbrain-core` which contains shared utilities and interfaces used by all other modules.
* `secondbrain-service` which orchestrates the function calling with the LLM.
* `secondbrain-tools` which contains the tools that interact with external data sources.
* `secondbrain-web` which is a web interface for interacting with the service.
* `secondbrain-cli` which is a CLI tool for interacting with the service.

# Configuration

SecondBrain is configured via MicroProfile Config. The supplied `SlackChannel` tool requires the following
configuration to support Oauth logins. Note that MicroProfile allows these configuration values to be set via
[a number of different locations](https://smallrye.io/smallrye-config/Main/config/getting-started/), including environment variables, system properties, and configuration files:

* `sb.slack.clientid` - The Slack client ID
* `sb.slack.clientsecret` - The Slack client secret

Other common configuration values include:

* `sb.ollama.url` - The URL of the Ollama service (defaults to http://localhost:11434)
* `sb.ollama.model` - The model to use in Ollama (defaults to `llama3.2`)
* `sb.encryption.password` - The password to use for encrypting sensitive data stored by web clients (defaults to
  `12345678`)

## Supported LLMs

SecondBrain is built around [Ollama](https://ollama.com/), which a is a local service exposing a huge selection of LLMs.
This ensures that your data and prompts are kept private and secure.

## Example Usages

An example usage of SecondBrain is a prompt like this:

```
Summarize 7 days worth of messages from the #announcements channel in Slack
```

This prompt is handled like this:

1. The prompt is passed to Ollama which selects the `secondbrain.tools.SlackChannel` tool. This is commonly referred to
   as function calling.
2. Ollama also extracts the channel name `#announcements` and days `7` from the prompt as an argument.
3. The `secondbrain.tools.SlackChannel` tool is called with the argument `#announcements` and `7`.
4. The tool uses the Slack API to find messages from the last `7` days in the `#announcements` channel.
5. The messages are placed in the context of the original prompt and passed back to Ollama.
6. Ollama answers the prompt with the messages context and returns the result to the user.

## Testing

* Install Ollama locally
* Pull the `llama3.2` model with the command `ollama pull llama3.2`
* Build and install all the modules with command `mvn clean install`
* Start Payara Micro with the command `cd web; mvn package; mvn payara-micro:start`
* Create a [GitHub access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)
* Open [http://localhost:8080/index.html](http://localhost:8080/index.html) in a browser, paste in the access token, and
  run the default query

## New Tools

See the `secondbrain.tools.HelloWorld` tool for an example of how to create a new tool.