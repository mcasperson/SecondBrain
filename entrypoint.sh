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

# List the available models
ollama list

# Run SecondBrain CLI
java \
  -Dsb.tools.force=GitHubDiffs \
  -Dsb.ollama.url=http://127.0.0.1:11434 \
  -Dsb.ollama.gitdiffmodel=qwen2.5-coder \
  -Dsb.ollama.toolmodel=llama3.2:3b \
  -Dsb.ollama.model=llama3.2:3b \
  -Dsb.github.accesstoken="$1" \
  -Dsb.github.owner="$2" \
  -Dsb.github.repo="$3" \
  -Dsb.github.sha="$4" \
  -jar /usr/local/bin/secondbrain-cli.jar "$5" >> /tmp/secondbrain-cli.log

cat /tmp/secondbrain-cli.log

if [ -n "$GITHUB_OUTPUT" ]; then
  {
    echo 'response<<EOF'
    cat /tmp/secondbrain-cli.log
    echo EOF
  } >> "$GITHUB_OUTPUT"
fi
