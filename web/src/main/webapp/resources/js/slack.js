document.addEventListener('DOMContentLoaded', (event) => {
    document.getElementById('prompt').value = stripLineBreaks(
        stripLeadingWhitespace(
            `Summarize 7 days worth of messages from the #announcements channel`))

    buildButtons();
    selectTokenInput();

    slackToken.addEventListener('input', buildButtons);
    form.addEventListener('submit', handleSubmit);
    tokenSelection.addEventListener('click', selectTokenInput);
    login.addEventListener('click', handleLogin);
    logout.addEventListener('click', handleLogout);
});

function getCookie(name) {
    const value = `; ${document.cookie}`;
    const parts = value.split(`; ${name}=`);
    if (parts.length === 2) return parts.pop().split(';').shift();
}

function stripLeadingWhitespace(text) {
    return text.split("\n").map(l => l.replace(/^\s+/g, '')).join("\n");
}

function stripLineBreaks(text) {
    return text.replace(/\n/g, ' ');
}

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

function handleSubmit(event) {
    event.preventDefault();

    disableForm()

    response.innerText = 'Loading...'

    const prompt = document.getElementById('prompt').value;
    const customModel = document.getElementById('customModel').value;
    const contextWindow = document.getElementById('contextWindow').value;

    savePrompt(prompt);
    displaySavedPrompts();

    const context = {
        custom_model: customModel,
        context_window: contextWindow
    };
    const tokenSelection = document.getElementById('tokenSelection');
    if (tokenSelection.checked) {
        context['slack_access_token'] = slackToken.value;
    }

    fetch('/api/promptweb?prompt=' + encodeURIComponent(prompt), {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(context),
    })
        .then(response => response.text())
        .then(data => {
            response.innerHTML = DOMPurify.sanitize(marked.parse(data))
        })
        .catch((error) => {
            response.innerText = error
        })
        .finally(() => {
            enableForm()
        });
}

function disableForm() {
    document.getElementById('prompt').disabled = true;
    document.querySelectorAll('input').forEach(b => b.disabled = true);
    document.querySelectorAll('select').forEach(b => b.disabled = true);
    document.querySelectorAll('button').forEach(b => b.disabled = true);
}

function enableForm() {
    document.getElementById('prompt').disabled = false;
    document.querySelectorAll('input').forEach(b => b.disabled = false);
    document.querySelectorAll('select').forEach(b => b.disabled = false);
    document.querySelectorAll('button').forEach(b => b.disabled = false);
}

function handleLogin() {
    window.location.href = 'https://slack.com/oauth/v2/authorize'
        + '?user_scope=channels:history,channels:read,search:read'
        + '&client_id=' + encodeURIComponent(clientId)
        + '&state=/slack.xhtml';
}

function handleLogout() {
    document.cookie = 'session=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/';
    buildButtons();
}

function buildButtons() {
    if (tokenSelection.checked) {
        submit.disabled = !slackToken.value;
    } else {
        const session = getCookie('session');
        if (session) {
            login.style.display = 'none';
            logout.style.display = 'inherit';
            submit.disabled = false;
        } else {
            login.style.display = 'inherit';
            logout.style.display = 'none';
            submit.disabled = true;
        }
    }
}

function selectTokenInput() {
    const tokenSelection = document.getElementById('tokenSelection');
    if (tokenSelection.checked) {
        loginButtonParent.style.display = 'none';
        tokenInputParent.style.display = 'inherit';
    } else {
        loginButtonParent.style.display = 'inherit';
        tokenInputParent.style.display = 'none';
    }

    buildButtons();
}

// Initial display of saved prompts
displaySavedPrompts();