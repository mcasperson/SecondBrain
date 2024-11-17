$global:stdOut = [System.Text.StringBuilder]::new()
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

    $global:stdOut.Clear()
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

    Register-ObjectEvent -InputObject $p -EventName "OutputDataReceived" -Action {
        #Write-Host $EventArgs.Data
        $global:stdOut.AppendLine($EventArgs.Data)
    } | Out-Null

    Register-ObjectEvent -InputObject $p -EventName "ErrorDataReceived" -Action {
        #Write-Host $EventArgs.Data
        $global:stdErr.AppendLine($EventArgs.Data)
    } | Out-Null

    Register-ObjectEvent -InputObject $p -EventName "Exited" -action {
        $global:myprocessrunning = $false
    } | Out-Null

    $p.StartInfo = $pinfo
    $p.Start() | Out-Null

    $p.BeginErrorReadLine()
    $p.BeginOutputReadLine()

    # Wait 10 minutes before forcibly killing the process
    $processTimeout = 1000 * 60 * 10
    while (($global:myprocessrunning -eq $true) -and ($processTimeout -gt 0))
    {
        # We must use lots of shorts sleeps rather than a single long one otherwise events are not processed
        $processTimeout -= 50
        Start-Sleep -m 50
    }
    if ($processTimeout -le 0)
    {
        $p.Kill()
    }

    $executionResults = [pscustomobject]@{
        StdOut = $global:stdOut.ToString()
        StdErr = $global:stdErr.ToString()
        ExitCode = $p.ExitCode
    }

    return $executionResults
}

# Powershell has to be set to parse the output of an executable as UTF8
# Java will print to std out as UTF 8 by passing -Dstdout.encoding=UTF-8
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8

$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"

# Replace the location of the Jar file with your copy of the CLI UberJAR
$result = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" -jar $jarFile `"Summarize 7 days worth of messages from the '$( $args[0] )' Slack channel in the style of a news article with up to 3 paragraphs. You can use fewer paragraphs if there is only a small amount of chat text to summarize. Use plain and professional language. You will be penalized for using emotive or excited language.`" markdn"
$ticketResult = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" -jar $jarFile `"Summarize 7 days worth of ZenDesk tickets from the '$( $args[1] )' organization in the style of a news article with up to 3 paragraphs. You can use fewer paragraphs if there is only a small amount of chat text to summarize. Use plain and professional language. You will be penalized for using emotive or excited language.`" markdn"

if ($args.Length -ge 3)
{
    $docsResult = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" -jar $jarFile `"Summarize the Google doc with id '$( $args[2] )'.`" markdn"
}

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

if ($result.StdOut -ne "" -or $ticketResult.StdOut -ne "" -or $docsResult.StdOut -ne "")
{
    # Replace this URL with your own Slack web hook
    $uriSlack = $env:SB_SLACK_CUSTOMER_WEBHOOK
    $body = ConvertTo-Json @{
        type = "mrkdwn"
        text = $text
    }

    try
    {
        Invoke-RestMethod -uri $uriSlack -Method Post -body $body -ContentType 'application/json' | Out-Null
    }
    catch
    {
        Write-Error (Get-Date) ": Update to Slack went wrong..."
    }
}


