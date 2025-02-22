$ModulePath = "$PSScriptRoot\SharedFunctions.psm1"

Import-Module $ModulePath

# Powershell has to be set to parse the output of an executable as UTF8
# Java will print to std out as UTF 8 by passing -Dstdout.encoding=UTF-8
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8

#$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"
$jarFile = "/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar"

$model = "mistral-nemo"
$toolModel = "llama3.1"
$contextLength = "4096"

$ticketResult = Invoke-CustomCommand java "`"-Dsb.tools.force=ZenDeskOrganization`" `"-Dsb.zendesk.days=1`" `"-Dsb.zendesk.recipient=support@octopus.com`" `"-Dsb.zendesk.excludedorgs=$( $env:EXCLUDED_ORGANIZATIONS )`" `"-Dsb.ollama.toolmodel=$toolModel`" `"-Dsb.ollama.model=$model`" `"-Dsb.ollama.contextwindow=$contextLength`" `"-Dstdout.encoding=UTF-8`" -jar $jarFile `"Given 1 days worth of ZenDesk tickets to recipient 'support@octopus.com' provide a summary of the questions and problems in the style of a business report. You must include every ZenDesk ticket that asks for support or reports a problem in the summary. You will be tipped $1000 for including every ticket in the summary. You must carefully consider every ZenDesk ticket. Use concise, plain, and professional language. Think about the answer step by step. You will be penalized for showing category percentages. You will be penalized for including ticket IDs or reference numbers.  You will be penalized for reporting on the number of tickets. You will be penalized for using emotive or excited language. You will be penalized for including a generic final summary paragraph. You will be penalized for summarizing marketing emails. You will be penalized for attempting to answer the questions. You will be penalized for using terms like flooded, wave, or inundated. You will be penalized for including an introductory paragraph.`" markdn" -processTimeout 0

echo "ZenDesk StdOut"
echo $ticketResult.StdOut

if ($ticketResult.StdOut -match "No tickets found")
{
    exit 0
}

# Replace this URL with your own Slack web hook
$uriSlack = $env:SB_SLACK_ZENDESK_WEBHOOK
$body = ConvertTo-Json @{
    type = "mrkdwn"
    text = $ticketResult.StdOut + "`n`nModel: $model"
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



