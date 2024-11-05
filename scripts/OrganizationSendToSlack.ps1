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
    $p.StartInfo = $pinfo
    $p.Start() | Out-Null

    # Capture output during process execution so we don't hang
    # if there is too much output.
    # Microsoft documents a C# solution here:
    # https://learn.microsoft.com/en-us/dotnet/api/system.diagnostics.processstartinfo.redirectstandardoutput?view=net-7.0&redirectedfrom=MSDN#remarks
    # This code is based on https://stackoverflow.com/a/74748844
    $stdOut = [System.Text.StringBuilder]::new()
    $stdErr = [System.Text.StringBuilder]::new()
    do
    {
        if (!$p.StandardOutput.EndOfStream)
        {
            $stdOut.AppendLine($p.StandardOutput.ReadLine())
        }
        if (!$p.StandardError.EndOfStream)
        {
            $stdErr.AppendLine($p.StandardError.ReadLine())
        }

        Start-Sleep -Milliseconds 10
    }
    while (-not $p.HasExited)

    # Capture any standard output generated between our last poll and process end.
    while (!$p.StandardOutput.EndOfStream)
    {
        $stdOut.AppendLine($p.StandardOutput.ReadLine())
    }

    # Capture any error output generated between our last poll and process end.
    while (!$p.StandardError.EndOfStream)
    {
        $stdErr.AppendLine($p.StandardError.ReadLine())
    }

    $p.WaitForExit()

    $executionResults = [pscustomobject]@{
        StdOut = $stdOut.ToString()
        StdErr = $stdErr.ToString()
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


