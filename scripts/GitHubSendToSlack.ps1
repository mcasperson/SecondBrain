Param (
    [string]$githubOwner,
    [string]$githubRepo
)

$ModulePath = "$PSScriptRoot\SharedFunctions.psm1"

Import-Module $ModulePath

# Powershell has to be set to parse the output of an executable as UTF8
# Java will print to std out as UTF 8 by passing -Dstdout.encoding=UTF-8
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8

#$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"
$jarFile = "/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar"

$model = "mistral-nemo"
$gitDiffModel = "qwen2.5-coder:14b"
$toolModel = "llama3.1"
$contextLength = "16384"

$ticketResult = Invoke-CustomCommand java "`"-Dsb.tools.force=GitHubDiffs`" `"-Dsb.github.owner=$githubOwner`" `"-Dsb.github.repo=$githubRepo`" `"-Dsb.github.days=1`" `"-Dsb.github.branch=main`" `"-Dsb.ollama.gitdiffmodel=$gitDiffModel`" `"-Dsb.ollama.toolmodel=$toolModel`" `"-Dsb.ollama.model=$model`" `"-Dsb.ollama.contextwindow=$contextLength`" `"-Dsb.ollama.diffcontextwindow=$contextLength`" `"-Dstdout.encoding=UTF-8`" -jar $jarFile `"Given the git diffs, provide a summary of the changes. Use plain language. You will be penalized for offering code suggestions. You will be penalized for sounding excited about the changes.`" markdn"

echo "ZenDesk StdOut for $githubOwner and $githubRepo"
echo $ticketResult.StdOut

#if ($ticketResult.StdOut -match "No diffs found")
#{
#    exit 0
#}

# Replace this URL with your own Slack web hook
$uriSlack = $env:SB_SLACK_GITHUB_WEBHOOK
$body = ConvertTo-Json @{
    type = "mrkdwn"
    text = $ticketResult.StdOut + "`n`nModel: $model`n`nGit Diff Model: $gitDiffModel"
}

try
{
    Invoke-RestMethod -uri $uriSlack -Method Post -body $body -ContentType 'application/json' | Out-Null
}
catch
{
    Write-Error "$( Get-Date ) : Update to Slack went wrong..."
    Write-Error (Get-FullException $_)
}



