$ModulePath = "$PSScriptRoot\SharedFunctions.psm1"

Import-Module $ModulePath

# Powershell has to be set to parse the output of an executable as UTF8
# Java will print to std out as UTF 8 by passing -Dstdout.encoding=UTF-8
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8

#$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"
$jarFile = "/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar"

$toolModel = "llama3.1"
$model = "llama3.1"
$contextWindow = "32768"

$result = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" `"-Dsb.tools.force=SlackZenGoogle`"  `"-Dsb.ollama.contextwindow=$contextWindow`" `"-Dsb.exceptions.printstacktrace=false`" `"-Dsb.slack.days=7`" `"-Dsb.google.doc=$( $args[2] )`" `"-Dsb.slack.channel=$( $args[0] )`" `"-Dsb.zendesk.organization=$( $args[1] )`" `"-Dsb.ollama.toolmodel=$toolModel`" `"-Dsb.ollama.model=$model`" -jar $jarFile `"Provide a summary of the slack messages, zendesk tickets, and planhat activities associated with $( $args[1] ). Only reference the contents of the google document when it relates to content in the slack messages, zendesk tickets, or planhat activities. You will be penalized for mentioning that there is no google document. If there are no zendesk tickets, say so. If there are no slack messages, say so. If there are no planhat activities, say so. You will be penalized for saying that you will monitor for tickets or messages in future.`" markdn"

echo "Slack StdOut"
echo $result.StdOut

#echo "Slack StdErr"
#echo $result.StdErr

if (-not [string]::IsNullOrWhitespace($result.StdOut))
{
    $text = "*=== " + $args[1] + " summary ===*`n`n" + $result.StdOut

    # Replace this URL with your own Slack web hook
    $uriSlack = $env:SB_SLACK_CUSTOMER_WEBHOOK
    $body = ConvertTo-Json @{
        type = "mrkdwn"
        text = $text
    }

    echo $text

    try
    {
        Invoke-RestMethod -uri $uriSlack -Method Post -body $body -ContentType 'application/json' | Out-Null
    }
    catch
    {
        Write-Error "$( Get-Date ) : Update to Slack went wrong..."
        Write-Error (Get-FullException $_)
    }
}


