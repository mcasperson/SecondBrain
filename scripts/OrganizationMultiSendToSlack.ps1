$ModulePath = "$PSScriptRoot\SharedFunctions.psm1"

Import-Module $ModulePath

# Powershell has to be set to parse the output of an executable as UTF8
# Java will print to std out as UTF 8 by passing -Dstdout.encoding=UTF-8
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8

#$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"
$jarFile = "/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar"

$toolModel = "llama3.1"
$model = "llama3.2"
$contextWindow = "32768"
$days = "7"

$response = Get-Content -Path $env:sb_multislackzengoogle_url -Raw

# https://github.com/jborean93/PowerShell-Yayaml
$database = ConvertFrom-Yaml $response

foreach ($entity in $database.entities)
{
    if ($entity.disabled)
    {
        continue
    }

    $entityName = $entity.name

    echo "Processing $entityName"

    $result = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" `"-Dsb.tools.force=MultiSlackZenGoogle`"  `"-Dsb.ollama.contextwindow=$contextWindow`" `"-Dsb.exceptions.printstacktrace=false`" `"-Dsb.multislackzengoogle.days=$days`" `"-Dsb.multislackzengoogle.entity=$entityName`" `"-Dsb.ollama.toolmodel=$toolModel`" `"-Dsb.ollama.model=$model`" -jar $jarFile `"Write a business report based on the the last $days days worth of slack messages, zendesk tickets, and planhat activities associated with $entityName. The google document must only be used to add supporting context to the contents of the zen desk tickets, planhat activities, and slcak messaes. You will be penalized for referecing slack messages, zen desk tickets, or plan hat activities that were not supplied in the prompt. You will be penalized for including a general summary of the google document in the report. You will be penalized for mentioning that there is no google document, slack messages, zendesk tickets, or planhat activities. You will be penalized for saying that you will monitor for tickets or messages in future. You will be penalized for for metioning a date range or period covered. You must assume the reader knows the number of days that the report covers.`" markdn"

    echo "Slack StdOut"
    echo $result.StdOut

    #echo "Slack StdErr"
    #echo $result.StdErr

    if (-not [string]::IsNullOrWhitespace($result.StdOut) -and -not $result.StdOut.Contains("InsufficientContext"))
    {
        $text = "*=== $entityName summary ===*`n`n" + $result.StdOut

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
}




