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
    while (($global:myprocessrunning -eq $true) -and ($processTimeout -gt 0))
    {
        # We must use lots of shorts sleeps rather than a single long one otherwise events are not processed
        $processTimeout -= 50
        Start-Sleep -m 50
    }

    $output = $p.StandardOutput.ReadToEnd()

    if ($processTimeout -le 0)
    {
        $p.Kill()
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

$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"

$result = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" -jar $jarFile `"Summarize 7 days worth of messages from the '$( $args[0] )' Slack channel in the style of a news article with up to 3 paragraphs. You can use fewer paragraphs if there is only a small amount of chat text to summarize. Use plain and professional language. You will be penalized for using emotive or excited language.`" markdn"

echo "Slack StdOut"
echo $result.StdOut

#echo "Slack StdErr"
#echo $result.StdErr

if ($args.Length -ge 2)
{
    $ticketResult = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" -jar $jarFile `"Summarize 7 days worth of ZenDesk tickets from the '$( $args[1] )' organization in the style of a news article with up to 3 paragraphs. You can use fewer paragraphs if there is only a small amount of chat text to summarize. Use plain and professional language. You will be penalized for using emotive or excited language.`" markdn"

    echo "ZenDesk StdOut"
    echo $ticketResult.StdOut
}

if ($args.Length -ge 3)
{
    $docsResult = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" -jar $jarFile `"Summarize the Google doc with id '$( $args[2] )'.`" markdn"

    echo "Google StdOut"
    echo $docsResult.StdOut
}

if (-not [string]::IsNullOrWhitespace($result.StdOut) -or -not [string]::IsNullOrWhitespace($ticketResult.StdOut) -or -not [string]::IsNullOrWhitespace($docsResult.StdOut))
{
    $text = "*=== " + $args[1] + " summary ===*`n`n"

    if (-not [string]::IsNullOrWhitespace($result.StdOut))
    {
        $text += "*Slack*`n" + $result.StdOut + "`n`n"
    }

    if (-not [string]::IsNullOrWhitespace($ticketResult.StdOut))
    {
        $text += "*ZenDesk*`n" + $ticketResult.StdOut + "`n`n"
    }

    if (-not [string]::IsNullOrWhitespace($docsResult.StdOut))
    {
        $text += "*CDJ*`n" + $docsResult.StdOut
    }

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


