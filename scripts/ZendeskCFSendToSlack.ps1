$global:stdErr = [System.Text.StringBuilder]::new()
$global:myprocessrunning = $true

Function Invoke-CustomCommand
{
    Param (
        $commandPath,
        $commandArguments,
        $workingDir = (Get-Location),
        $path = @()
    )

    $global:stdErr.Clear()
    $global:myprocessrunning = $true

    $path += $env:PATH
    $newPath = $path -join [IO.Path]::PathSeparator

    $pinfo = New-Object System.Diagnostics.ProcessStartInfo
    $pinfo.FileName = $commandPath
    $pinfo.WorkingDirectory = $workingDir
    $pinfo.RedirectStandardError = $true
    $pinfo.RedirectStandardOutput = $true
    $pinfo.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $pinfo.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $pinfo.UseShellExecute = $false
    $pinfo.Arguments = $commandArguments
    $pinfo.EnvironmentVariables["PATH"] = $newPath
    $p = New-Object System.Diagnostics.Process

    # https://learn.microsoft.com/en-us/dotnet/api/system.diagnostics.process.standardoutput
    # Reading from one stream must be async
    # We read the error stream, because events can be handled out of order,
    # and it is better to have this happen with debug output
    Register-ObjectEvent -InputObject $p -EventName "ErrorDataReceived" -Action {
        $global:stdErr.AppendLine($EventArgs.Data)
    } | Out-Null

    # We must wait for the Exited event rather than WaitForExit()
    # because WaitForExit() can result in events being missed
    # https://stackoverflow.com/questions/13113624/captured-output-of-command-run-by-powershell-is-sometimes-incomplete
    Register-ObjectEvent -InputObject $p -EventName "Exited" -action {
        $global:myprocessrunning = $false
    } | Out-Null

    $p.StartInfo = $pinfo
    $p.Start() | Out-Null

    $p.BeginErrorReadLine()

    # Wait 30 minutes before forcibly killing the process
    $processTimeout = 1000 * 60 * 30
    $lastUpdate = 0
    while (($global:myprocessrunning -eq $true) -and ($processTimeout -gt 0))
    {
        # We must use lots of shorts sleeps rather than a single long one otherwise events are not processed
        $processTimeout -= 50
        Start-Sleep -m 50

        $lastUpdate -= 50

        if ($lastUpdate -lt 0)
        {
            $lastUpdate = 1000 * 10
            Write-Host "Still running... $( $processTimeout / 1000 ) seconds left"
        }
    }

    if ($p.StandardOutput.Peek() -gt -1)
    {
        $output = $p.StandardOutput.ReadToEnd()
    }
    else
    {
        $output = ""
    }

    if ($processTimeout -le 0)
    {
        $p.Kill($true)
    }

    $executionResults = [pscustomobject]@{
        StdOut = $output
        StdErr = $global:stdErr.ToString()
        ExitCode = $p.ExitCode
    }

    return $executionResults
}

Function Get-FullException
{
    Param ($err)

    $e = $err.Exception

    $msg = $e.Message
    while ($e.InnerException)
    {
        $e = $e.InnerException
        $msg += "`n" + $e.Message
    }
    return $msg
}

# Powershell has to be set to parse the output of an executable as UTF8
# Java will print to std out as UTF 8 by passing -Dstdout.encoding=UTF-8
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8

#$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"
$jarFile = "/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar"

$model = "mistral-nemo"
$toolModel = "llama3.1"
$contextLength = "4096"

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



