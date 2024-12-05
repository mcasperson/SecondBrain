document.addEventListener('DOMContentLoaded', (event) => {
    const defaultPrompt = new URLSearchParams(window.location.search).get('prompt');

    document.getElementById('prompt').value = stripLineBreaks(
        stripLeadingWhitespace(
            defaultPrompt || `Summarize the Google document with the id 195j9eDD3ccgjQRttHhJPymLJUCOUjs-jmwTrekvdjFE`))

    buildButtons();
    selectTokenInput();

    googleToken.addEventListener('input', buildButtons);
    form.addEventListener('submit', handleSubmit);
    login.addEventListener('click', handleLogin);
    logout.addEventListener('click', handleLogout);
    authLogin.addEventListener('click', selectTokenInput);
    authServiceAccount.addEventListener('click', selectTokenInput);
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

    const authLoginEnabled = document.getElementById('authLogin').checked;

    if (authLoginEnabled) {
        postRequest(prompt, {});
    } else {
        const file = googleToken.files[0];
        if (file) {
            const reader = new FileReader();
            reader.onload = function (e) {
                const content = e.target.result;
                const context = {google_service_account_json: content}
                postRequest(prompt, context);
            };
            reader.readAsText(file);


        } else {
            alert("Please select a file.");
        }
    }
}

function postRequest(prompt, context) {
    const customModel = document.getElementById('customModel').value;
    const argumentDebugging = document.getElementById('argumentDebugging').checked;

    context['custom_model'] = customModel;
    context['argument_debugging'] = argumentDebugging;

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
    document.querySelectorAll('button').forEach(b => b.disabled = true);
    document.querySelectorAll('textarea').forEach(b => b.disabled = true);
    document.querySelectorAll('input').forEach(b => b.disabled = true);
    document.querySelectorAll('select').forEach(b => b.disabled = true);
}

function enableForm() {
    document.querySelectorAll('button').forEach(b => b.disabled = false);
    document.querySelectorAll('textarea').forEach(b => b.disabled = false);
    document.querySelectorAll('input').forEach(b => b.disabled = false);
    document.querySelectorAll('select').forEach(b => b.disabled = false);
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
    const authLoginEnabled = document.getElementById('authLogin').checked;

    if (authLoginEnabled) {
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
    } else {
        submit.disabled = googleToken.files.length === 0;
    }
}

function selectTokenInput() {
    const authLoginEnabled = document.getElementById('authLogin').checked;

    if (authLoginEnabled) {
        loginButtonParent.style.display = 'inherit';
        serviceAccountParent.style.display = 'none';
    } else {
        loginButtonParent.style.display = 'none';
        serviceAccountParent.style.display = 'inherit';
    }

    buildButtons();
}