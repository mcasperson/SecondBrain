SecondBrain is a CLI tool to access and filter data from external data sources such as Salesforce, ZenDesk, Slack, Gong and others, and then query that data using an LLM.

This is useful for generating reports, summaries, and insights from multiple and otherwise disconnected data sources.

## Example

Clone the git repository:

```bash
git clone https://github.com/mcasperson/SecondBrain.git
```

Build the project using Maven:

```bash
./mvnw clean package -DskipTests
```

Run the `DirectoryScan` tool against the PDF files in the `samples` directory, looking for keywords "AI", "Kubernetes", and "K8s", and then ask a question about the data found. Replace the `replaceme` values with your Azure AI Foundry API key and endpoint URL:

```bash
java \
    "-Dsb.llm.client=azure" \
    "-Dsb.azurellm.apikey=replaceme" \
    "-Dsb.azurellm.url=https://replaceme.services.ai.azure.com/models/chat/completions?api-version=2024-05-01-preview" \
    "-Dsb.tools.force=DirectoryScan" \
    "-Dsb.directoryscan.directory=samples" \
    "-Dsb.directoryscan.keywords=AI,Kubernetes,K8s" \
    -jar cli/target/secondbrain-cli-1.0-SNAPSHOT.jar \
    "What percentage of AI deployments use Kubernetes or K8s for orchestration?"
```