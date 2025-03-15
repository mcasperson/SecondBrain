# Must use globals here
# https://stackoverflow.com/a/4059007/157605
$global:stdErr = [System.Text.StringBuilder]::new()
$global:stdOut = [System.Text.StringBuilder]::new()
$global:myprocessrunning = $true

Function New-TempDir
{
    # Create a temporary file
    $tempFile = New-TemporaryFile

    # Get the directory of the temporary file
    $tempDir = Split-Path -Parent $tempFile.FullName

    # Remove the temporary file
    Remove-Item $tempFile.FullName | Out-Null

    $subDir = $tempDir + "/" + $( New-Guid )

    mkdir $subDir | Out-Null

    return $subDir
}

Function Invoke-CustomCommand
{
    Param (
        $commandPath,
        $commandArguments,
        $workingDir = (Get-Location),
        $path = @(),
        $processTimeout = 1000 * 60 * 30
    )

    $remainingTimeout = $processTimeout
    $executionTime = 0
    $lastUpdate = 0

    $global:stdErr.Clear()
    $global:stdOut.Clear()
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

    # This isn't called because we don't use BeginOutputReadLine()
    # It is left here for reference
    Register-ObjectEvent -InputObject $p -EventName "OutputDataReceived" -Action {
        $global:stdOut.AppendLine($EventArgs.Data)
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

    while (($global:myprocessrunning -eq $true) -and (($remainingTimeout -gt 0) -or ($processTimeout -le 0)))
    {
        # We must use lots of shorts sleeps rather than a single long one otherwise events are not processed
        if ($processTimeout -gt 0)
        {
            $remainingTimeout -= 50
        }

        $executionTime += 50

        Start-Sleep -m 50

        $lastUpdate -= 50

        if ($lastUpdate -lt 0)
        {
            $lastUpdate = 1000 * 10

            if ($processTimeout -gt 0)
            {
                Write-Host "Still running... $( $executionTime / 1000 ) seconds, $( $remainingTimeout / 1000 ) seconds left" -ForegroundColor yellow
            }
            else
            {
                Write-Host "Still running... $( $executionTime / 1000 ) seconds" -ForegroundColor yellow
            }

            $tail = 1000

            $tailStdOut = if ($global:stdOut.ToString().Length -gt $tail)
            {
                $global:stdOut.ToString().Substring($global:stdOut.ToString().Length - $tail)
            }
            else
            {
                $global:stdOut.ToString()
            }
            Write-Host "StdOut: $tailStdOut"

            $tailStdErr = if ($global:stdErr.ToString().Length -gt $tail)
            {
                $global:stdErr.ToString().Substring($global:stdErr.ToString().Length - $tail)
            }
            else
            {
                $global:stdErr.ToString()
            }
            Write-Host "StdErr: $tailStdErr"
        }
    }

    if (($remainingTimeout -le 0) -and ($processTimeout -gt 0))
    {
        $p.Kill($true)
        $output = ""
        $exitCode = -1
        Write-Host "Killed process"
    }
    else
    {
        $output = $p.StandardOutput.ReadToEnd()
        $exitCode = $p.ExitCode
    }

    $executionResults = [pscustomobject]@{
        StdOut = $output
        StdErr = $global:stdErr.ToString()
        ExitCode = $exitCode
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

Function Get-SplitTrimmedAndJoinedString
{
    param (
        [string]$inputString
    )

    $lines = $inputString -split "`n"
    $trimmedLines = $lines | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }
    $result = $trimmedLines -join " "
    return $result
}