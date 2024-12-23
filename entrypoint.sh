#!/bin/sh -l

# Start Ollama
nohup bash -c "ollama serve &"
wait4x http http://127.0.0.1:11434

# An OK response from the server is not quite enough to know that it is ready to accept requests.
# https://github.com/ollama/ollama/issues/1378
max_retries=10
retry_count=0
until ollama --version && ollama ps; do
  retry_count=$((retry_count + 1))
  if [ $retry_count -ge $max_retries ]; then
    echo "Command failed after $retry_count attempts."
    exit 1
  fi
  echo "Command failed. Retrying... ($retry_count/$max_retries)"
  sleep 1  # Wait for 1 second before retrying
done

# If the models are not baked into the Docker image, pull them down
ollama pull "$1"
ollama pull "$2"

# List the available models
ollama list

# Run SecondBrain CLI. Note the context window needs to be be reasonably small,
# as the hosted GitHub runners only have around 18GB of memory free.
java \
  -Dsb.tools.force=GitHubDiffs \
  -Dsb.ollama.url=http://127.0.0.1:11434 \
  -Dsb.ollama.gitdiffmodel="$2" \
  -Dsb.ollama.toolmodel=llama3.2:3b \
  -Dsb.ollama.model="$1" \
  -Dsb.ollama.contextwindow=8192 \
  -Dsb.ollama.diffcontextwindow=8192 \
  -Dsb.github.accesstoken="$3" \
  -Dsb.github.owner="$4" \
  -Dsb.github.repo="$5" \
  -Dsb.github.sha="$6" \
  -jar /usr/local/bin/secondbrain-cli.jar "$5" >> /tmp/secondbrain-cli.log

cat /tmp/secondbrain-cli.log

if [ -n "$GITHUB_OUTPUT" ]; then
  {
    echo 'response<<EOF'
    cat /tmp/secondbrain-cli.log
    echo EOF
  } >> "$GITHUB_OUTPUT"
fi
