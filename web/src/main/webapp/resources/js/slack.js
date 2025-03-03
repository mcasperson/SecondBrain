document.addEventListener('DOMContentLoaded', (event) => {
    buildButtons();
    selectTokenInput();

    slackToken.addEventListener('input', buildButtons);
    form.addEventListener('submit', handleSubmit);
    tokenSelection.addEventListener('click', selectTokenInput);
    login.addEventListener('click', handleLogin);
    logout.addEventListener('click', handleLogout);
});

function handleSubmit(event) {
    event.preventDefault();

    disableForm()

    response.innerText = 'Loading - this can take several minutes, so please be patient...'

    const prompt = document.getElementById('prompt').value;
    const customModel = document.getElementById('customModel').value;
    const contextWindow = document.getElementById('contextWindow').value;
    const slackChannel = document.getElementById('channel').value;
    const slackDays = document.getElementById('days').value;

    savePrompt(prompt);
    displaySavedPrompts();

    const context = {
        tool: "SlackChannel",
        custom_model: customModel,
        context_window: contextWindow,
        slack_channel: slackChannel,
        slack_days: slackDays,
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
        .then(key => getResult(key))
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

document.getElementById('days').value = localStorage.getItem('slackDays') || '7';
document.getElementById('channel').value = localStorage.getItem('slackChannel') || 'announcements';
document.getElementById('contextWindow').value = localStorage.getItem('slackContextWindow') || '65536';
document.getElementById('customModel').value = localStorage.getItem('slackCustomModel') || '';
document.getElementById('prompt').value = localStorage.getItem('slackPrompt') || stripLineBreaks(
    stripLeadingWhitespace(
        `Summarize 7 days worth of messages from the #announcements channel`));

document.getElementById('customModel').addEventListener("onchange", function () {
    localStorage.setItem('slackDays', document.getElementById('days').value);
}, false)

document.getElementById('customModel').addEventListener("onchange", function () {
    localStorage.setItem('slackChannel', document.getElementById('channel').value);
}, false)

document.getElementById('customModel').addEventListener("onchange", function () {
    localStorage.setItem('slackContextWindow', document.getElementById('contextWindow').value);
}, false)

document.getElementById('customModel').addEventListener("change", function () {
    localStorage.setItem('slackCustomModel', document.getElementById('customModel').value)
}, false)

document.getElementById('customModel').addEventListener("onchange", function () {
    localStorage.setItem('slackPrompt', document.getElementById('prompt').value);
}, false)