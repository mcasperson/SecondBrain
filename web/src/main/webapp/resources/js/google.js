document.addEventListener('DOMContentLoaded', (event) => {
    document.getElementById('prompt').value = stripLineBreaks(
        stripLeadingWhitespace(
            `Summarize the Google document with the id 195j9eDD3ccgjQRttHhJPymLJUCOUjs-jmwTrekvdjFE`))

    buildButtons();
    selectTokenInput();

    googleToken.addEventListener('input', buildButtons);
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

function handleSubmit(event) {
    event.preventDefault();

    disableForm()

    response.innerText = 'Loading...'

    const prompt = document.getElementById('prompt').value;


    const context = {};
    const tokenSelection = document.getElementById('tokenSelection');
    if (tokenSelection.checked) {
        context['google_access_token'] = googleToken.value;
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
            response.innerHTML = marked.parse(data)
        })
        .catch((error) => {
            response.innerText = error
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
    window.location.href = 'https://accounts.google.com/o/oauth2/v2/auth'
        + '?scope=https://www.googleapis.com/auth/documents.readonly'
        + '&client_id=' + clientId
        + '&redirect_uri=' + redirectUrl
        + '&response_type=code'
        + '&state=/google.xhtml';
}

function handleLogout() {
    document.cookie = 'session=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/';
    buildButtons();
}

function buildButtons() {
    if (tokenSelection.checked) {
        submit.disabled = !googleToken.value;
    } else {
        const session = getCookie('session');
        if (session && JSON.parse(atob(session))["google_access_token"]) {
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