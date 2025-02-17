Function Invoke-CustomCommand
{
    Param (
        $commandPath,
        $commandArguments,
        $workingDir = (Get-Location),
        $path = @(),
        $processTimeout = 1000 * 60 * 30
    )

    write-host "Running command: $commandPath $commandArguments" -ForegroundColor yellow

    $stdErr = [System.Text.StringBuilder]::new()
    $stdOut = [System.Text.StringBuilder]::new()
    $myprocessrunning = $true

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
        $script:stdErr.AppendLine($EventArgs.Data)
    }.GetNewClosure() | Out-Null

    Register-ObjectEvent -InputObject $p -EventName "OutputDataReceived" -Action {
        $script:stdOut.AppendLine($EventArgs.Data)
    }.GetNewClosure() | Out-Null

    # We must wait for the Exited event rather than WaitForExit()
    # because WaitForExit() can result in events being missed
    # https://stackoverflow.com/questions/13113624/captured-output-of-command-run-by-powershell-is-sometimes-incomplete
    Register-ObjectEvent -InputObject $p -EventName "Exited" -action {
        $script:myprocessrunning = $false
    }.GetNewClosure() | Out-Null

    $p.StartInfo = $pinfo
    $p.Start() | Out-Null

    $p.BeginErrorReadLine()

    $lastUpdate = 0
    while (($myprocessrunning -eq $true) -and ($processTimeout -gt 0))
    {
        # We must use lots of shorts sleeps rather than a single long one otherwise events are not processed
        $processTimeout -= 50
        Start-Sleep -m 50

        $lastUpdate -= 50

        if ($lastUpdate -lt 0)
        {
            $lastUpdate = 1000 * 10
            Write-Host "Still running... $( $processTimeout / 1000 ) seconds left" -ForegroundColor yellow

            $tail = 500

            $tailStdOut = if ($stdOut.ToString().Length -gt $tail)
            {
                $stdOut.ToString().Substring($stdOut.ToString().Length - $tail)
            }
            else
            {
                $stdOut.ToString()
            }
            Write-Host "StdOut: $tailStdOut"

            $tailStdErr = if ($stdErr.ToString().Length -gt $tail)
            {
                $stdErr.ToString().Substring($stdErr.ToString().Length - $tail)
            }
            else
            {
                $stdErr.ToString()
            }
            Write-Host "StdErr: $tailStdErr"
        }
    }

    if ($processTimeout -le 0)
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
        StdErr = $stdErr.ToString()
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