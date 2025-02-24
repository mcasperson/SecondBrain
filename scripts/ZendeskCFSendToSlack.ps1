$ModulePath = "$PSScriptRoot\SharedFunctions.psm1"

Import-Module $ModulePath

# Powershell has to be set to parse the output of an executable as UTF8
# Java will print to std out as UTF 8 by passing -Dstdout.encoding=UTF-8
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8

#$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"
$jarFile = "/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar"

$model = "mistral-nemo:12b-instruct-2407-q8_0"
$toolModel = "llama3.1"
$contextLength = "32768"

$ticketResult = Invoke-CustomCommand java "`"-Dsb.zendesk.user=$( $env:SB_ZENDESK_USER_CF )`" `"-Dsb.zendesk.url=$( $env:SB_ZENDESK_URL_CF )`" `"-Dsb.zendesk.accesstoken=$( $env:SB_ZENDESK_ACCESSTOKEN_CF )`" `"-Dsb.tools.force=ZenDeskOrganization`" `"-Dsb.zendesk.hours=8`" `"-Dsb.zendesk.numcomments=10`" `"-Dsb.ollama.toolmodel=$toolModel`" `"-Dsb.ollama.model=$model`" `"-Dsb.ollama.contextwindow=$contextLength`" `"-Dstdout.encoding=UTF-8`" -jar $jarFile `"Given 8 hours worth of ZenDesk tickets, provide a summary of the questions and problems in the style of a business report with up to 3 paragraphs. You must carefully consider each ticket when genering the summary. You will be penalized for showing category percentages. You will be penalized for including ticket IDs or reference numbers. Use concise, plain, and professional language. You will be penalized for using emotive or excited language. You will be penalized for including a generic final summary paragraph. You must only summarize emails that are asking for support. You will be penalized for summarizing emails that are not asking for support. You will be penalized for summarizing marketing emails. You will be penalized for using terms like flooded, wave, scrambling, or inundated. You must ignore stack traces.`" markdn"

echo "ZenDesk StdOut"
echo $ticketResult.StdOut

# Replace this URL with your own Slack web hook
$uriSlack = $env:SB_SLACK_ZENDESK_WEBHOOK_CF
$body = ConvertTo-Json @{
    type = "mrkdwn"
    text = $ticketResult.StdOut
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



