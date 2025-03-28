function getUniqueList(arr) {
    return [...new Set(arr)];
}

function savePrompt(tool, prompt) {
    prompt = prompt.split("\n").map(l => l.trim()).join(" ");
    let savedPrompts = JSON.parse(localStorage.getItem(tool + 'SavedPrompts')) || [];
    if (savedPrompts.length >= 5) {
        savedPrompts.shift();
    }
    savedPrompts.push(prompt);
    localStorage.setItem(tool + 'SavedPrompts', JSON.stringify(getUniqueList(savedPrompts)));
}

function displaySavedPrompts(tool) {
    const savedPrompts = getUniqueList(JSON.parse(localStorage.getItem(tool + 'SavedPrompts')) || []);
    const savedPromptsList = document.getElementById('savedPromptsList');
    savedPromptsList.innerHTML = '';
    savedPrompts.forEach((prompt, index) => {
        prompt = prompt.split("\n").map(l => l.trim()).join(" ");
        const listItem = document.createElement('li');
        const link = document.createElement('a');
        link.href = '#';
        link.title = prompt;
        link.innerText = prompt;
        link.style.display = 'block';
        link.style.whiteSpace = 'nowrap';
        link.style.overflow = 'hidden';
        link.style.textOverflow = 'ellipsis';
        link.onclick = function () {
            document.getElementById('prompt').value = prompt;
        };
        listItem.appendChild(link);
        savedPromptsList.appendChild(listItem);
    });
}