Param (
    [string]$companyNames,
    [string]$obsidianPath,
    [string]$googleDoc
)

$ModulePath = "$PSScriptRoot\SharedFunctions.psm1"

Import-Module $ModulePath

# Powershell has to be set to parse the output of an executable as UTF8
# Java will print to std out as UTF 8 by passing -Dstdout.encoding=UTF-8
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8

#$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"
$jarFile = "/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar"

#$model = "mistral-nemo:12b-instruct-2407-q8_0"
$model = "qwen3:32b"
$toolModel = "llama3.1"
$contextWindow = "32768"

$companyNames -split "," | ForEach-Object {
    $split = $_ -split ":"

    $companyName = $split[0]
    $keywords = $split -join ","

    Write-Host "Processing $companyName with keywords $keywords"

    $ticketResult = Invoke-CustomCommand java "`"-Dsb.tools.force=GoogleDocs`" `"-Dsb.ollama.contextwindow=$contextWindow`" `"-Dsb.google.doc=$googleDoc`" `"-Dsb.google.keywords=$keywords`" `"-Dsb.zendesk.excludedorgs=$( $env:EXCLUDED_ORGANIZATIONS )`" `"-Dsb.ollama.toolmodel=$toolModel`" `"-Dsb.ollama.model=$model`" `"-Dstdout.encoding=UTF-8`" -jar $jarFile `"You are given the Google document. Assume the document is written in the first person by Matthew Casperson, also known as Matt. List all the information about $companyName. Include a list of people and their job titles, platforms, and tools. List the number of projects, targets, and tenants. You will be penalize for including details about unrelated companies.`""

    Set-Content -Path "$obsidianPath\$companyName.md" -Value $ticketResult.StdOut
}


