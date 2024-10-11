# SecondBrain

SecondBrain is a platform that allows you to have a conversation with your data.

It works like this:

1. You enter a plain text prompt about your data, such as GitHub commits or Slack messages.
2. The prompt is passed to an LLM along with definitions of "tools" that can be used to interact with the data.
3. The LLM selects the correct tool to collect the required data.
4. The tool then collects the data, places it in the context of an LLM prompt, and passes the prompt and context to the
   LLM.
5. The LLM response is returned to the user.

The power in this platform is the ability to easily create new tools that interact with data sources. With a few simple
HTTP calls you can create a new tool that link into almost any external data source.

## Supported LLMs

SecondBrain is built around Ollama, which a is a local service exposing a huge selection of LLMs. This ensures that your
data and prompts are kept private and secure.

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

* Build and install all the modules with command `mvn clean install`
* Start Payara Micro with the command `cd web; mvn package; mvn payara-micro:start`
* Open [http://localhost:8080/index.html](http://localhost:8080/index.html) in a browser