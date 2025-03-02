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