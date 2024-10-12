document.addEventListener('DOMContentLoaded', (event) => {
    document.getElementById('prompt').value = stripLineBreaks(
        stripLeadingWhitespace(
            `Summarize 7 days worth of messages from the #announcements channel`))
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

function handleSubmit(event) {
    event.preventDefault();

    disableForm()

    response.value = 'Loading...'

    const prompt = document.getElementById('prompt').value;


    const context = {};
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
            response.value = data
        })
        .catch((error) => {
            response.value = error
        })
        .finally(() => {
            enableForm()
        });
}

function disableForm() {
    document.querySelectorAll('button').forEach(b => b.disabled = true);
    document.querySelectorAll('textarea').forEach(b => b.disabled = true);
    document.querySelectorAll('input').forEach(b => b.disabled = true);
}

function enableForm() {
    document.querySelectorAll('button').forEach(b => b.disabled = false);
    document.querySelectorAll('textarea').forEach(b => b.disabled = false);
    document.querySelectorAll('input').forEach(b => b.disabled = false);
}

function handleLogin() {
    window.location.href = 'https://slack.com/oauth/v2/authorize'
        + '?user_scope=channels:history,channels:read,search:read'
        + '&client_id=' + clientId
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

selectTokenInput();
buildButtons();