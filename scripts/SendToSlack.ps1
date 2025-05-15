$ModulePath = "$PSScriptRoot\SharedFunctions.psm1"

Import-Module $ModulePath

# Powershell has to be set to parse the output of an executable as UTF8
# Java will print to std out as UTF 8 by passing -Dstdout.encoding=UTF-8
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8

#$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"
$jarFile = "/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar"

$contextWindow = "32768"

$channelsYaml = Get-Content -Path $env:SB_CHANNELS_YAML -Raw
$channels = ConvertFrom-Yaml $channelsYaml

foreach ($channel in $channels.channels)
{
    # Default to shared prompt
    $prompt = $channels.prompt

    # But then try to use the channel specific prompt
    if (-not [string]::IsNullOrEmpty($channel.prompt))
    {
        $prompt = $channel.prompt
    }

    # Replace the location of the Jar file with your copy of the CLI UberJAR
    $result = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" `"-Dsb.ollama.contextwindow=$contextWindow`" `"-Dsb.ollama.model=$( $channel.model )`" `"-Dsb.tools.force=SlackChannel`" `"-Dsb.slack.channel=$( $channel.name )`"  `"-Dsb.slack.days=7`" -jar $jarFile `"$prompt`" markdn"

    echo $result

    echo $result.StdOut
    echo $result.StdErr

    # Replace this URL with your own Slack web hook
    $uriSlack = $env:SB_SLACK_GENERAL_WEBHOOK
    $body = ConvertTo-Json @{
        type = "mrkdwn"
        text = "*" + $channel.name + " summary*`n" + $result.StdOut
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
}
