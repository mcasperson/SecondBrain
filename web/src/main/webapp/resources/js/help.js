function setModelHelp() {
    const model = document.getElementById('customModel').value || "llama3.2"
    document.getElementById('customModelHelp').innerHTML = `
        These models must be
         <a href="https://github.com/ollama/ollama?tab=readme-ov-file#pull-a-model">pulled by Ollama</a>
         e.g. <code>ollama pull ` + model + `</code> (when running Ollama locally)
         or <code>docker exec secondbrain-ollama-1 ollama pull ` + model + `</code> (when running Ollama in Docker).`;
}

document.getElementById('customModel').addEventListener("change", setModelHelp, false)

setModelHelp()