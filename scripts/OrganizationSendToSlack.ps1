$global:stdOut = [System.Text.StringBuilder]::new()
$global:stdErr = [System.Text.StringBuilder]::new()

Function Invoke-CustomCommand
{
    Param (
        $commandPath,
        $commandArguments,
        $workingDir = (Get-Location),
        $path = @()
    )

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

    # See https://stackoverflow.com/questions/13113624/captured-output-of-command-run-by-powershell-is-sometimes-incomplete
    Register-ObjectEvent -InputObject $p -EventName "OutputDataReceived" -Action {
        $global:stdOut.AppendLine($EventArgs.Data)
    } | Out-Null

    Register-ObjectEvent -InputObject $p -EventName "ErrorDataReceived" -Action {
        $global:stdErr.AppendLine($EventArgs.Data)
    } | Out-Null

    $p.StartInfo = $pinfo
    $p.Start() | Out-Null

    $p.BeginErrorReadLine()
    $p.BeginOutputReadLine()

    $p.WaitForExit()

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


