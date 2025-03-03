document.addEventListener('DOMContentLoaded', (event) => {
    const defaultPrompt = new URLSearchParams(window.location.search).get('prompt');

    buildButtons();
    selectTokenInput();

    googleToken.addEventListener('input', buildButtons);
    form.addEventListener('submit', handleSubmit);
    login.addEventListener('click', handleLogin);
    logout.addEventListener('click', handleLogout);
    authLogin.addEventListener('click', selectTokenInput);
    authServiceAccount.addEventListener('click', selectTokenInput);

    document.getElementById('contextWindow').addEventListener("onchange", function () {
        localStorage.setItem('googleContextWindow', document.getElementById('contextWindow').value);
    }, false)

    document.getElementById('customModel').addEventListener("change", function () {
        localStorage.setItem('googleCustomModel', document.getElementById('customModel').value)
    }, false)

    document.getElementById('prompt').addEventListener("onchange", function () {
        localStorage.setItem('googlePrompt', document.getElementById('prompt').value);
    }, false)

    document.getElementById('contextWindow').value = localStorage.getItem('googleContextWindow') || '65536';
    document.getElementById('customModel').value = localStorage.getItem('googleCustomModel') || '';
    document.getElementById('prompt').value = localStorage.getItem('googlePrompt') || stripLineBreaks(
        stripLeadingWhitespace(
            defaultPrompt || `Summarize the Google document with the id 195j9eDD3ccgjQRttHhJPymLJUCOUjs-jmwTrekvdjFE`));

    displaySavedPrompts('googleDocs');
});

function handleSubmit(event) {
    event.preventDefault();

    disableForm()

    response.innerText = 'Loading - this can take several minutes, so please be patient...'

    const prompt = document.getElementById('prompt').value;
    const authLoginEnabled = document.getElementById('authLogin').checked;

    savePrompt('googleDocs', prompt);
    displaySavedPrompts('googleDocs');

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
    const contextWindow = document.getElementById('contextWindow').value;

    context['custom_model'] = customModel;
    context['argument_debugging'] = argumentDebugging;
    context['tool'] = "GoogleDocs";
    context['context_window'] = contextWindow;

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