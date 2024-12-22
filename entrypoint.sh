#!/bin/sh -l

# Start Ollama
nohup bash -c "ollama serve &"
wait4x http http://127.0.0.1:11434

# Run SecondBrain CLI
java \
  -Dsb.tools.force=GitHubDiffs \
  -Dsb.ollama.gitdiffmodel=qwen2 \
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
