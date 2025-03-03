function stripLeadingWhitespace(text) {
    return text.split("\n").map(l => l.replace(/^\s+/g, '')).join("\n");
}

function stripLineBreaks(text) {
    return text.replace(/\n/g, ' ');
}