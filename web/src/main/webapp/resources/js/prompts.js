function getUniqueList(arr) {
    return [...new Set(arr)];
}

function savePrompt(prompt) {
    prompt = prompt.split("\n").map(l => l.trim()).join(" ");
    let savedPrompts = JSON.parse(localStorage.getItem('savedPrompts')) || [];
    if (savedPrompts.length >= 5) {
        savedPrompts.shift();
    }
    savedPrompts.push(prompt);
    localStorage.setItem('savedPrompts', JSON.stringify(getUniqueList(savedPrompts)));
}

function displaySavedPrompts() {
    const savedPrompts = getUniqueList(JSON.parse(localStorage.getItem('savedPrompts')) || []);
    const savedPromptsList = document.getElementById('savedPromptsList');
    savedPromptsList.innerHTML = '';
    savedPrompts.forEach((prompt, index) => {
        prompt = prompt.split("\n").map(l => l.trim()).join(" ");
        const listItem = document.createElement('li');
        const link = document.createElement('a');
        link.href = '#';
        link.innerText = prompt;
        link.onclick = function () {
            document.getElementById('prompt').value = prompt;
        };
        listItem.appendChild(link);
        savedPromptsList.appendChild(listItem);
    });
}