Param (
    [string]$companyNames,
    [string]$obsidianPath,
    [string]$googleDoc
)

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

$model = "llama3.1"
$toolModel = "llama3.1"

$companyNames -split "," | ForEach-Object {
    $split = $_ -split ":"

    $companyName = $split[0]
    $keywords = $split -join ","

    Write-Host "Processing $companyName with keywords $keywords"

    $ticketResult = Invoke-CustomCommand java "`"-Dsb.tools.force=GoogleDocs`" `"-Dsb.ollama.contextwindow=8192`" `"-Dsb.google.doc=$googleDoc`" `"-Dsb.google.keywords=$keywords`" `"-Dsb.zendesk.excludedorgs=$( $env:EXCLUDED_ORGANIZATIONS )`" `"-Dsb.ollama.toolmodel=$toolModel`" `"-Dsb.ollama.model=$model`" `"-Dstdout.encoding=UTF-8`" -jar $jarFile `"You are given the Google document. Assume the document is written in the first person by Matthew Casperson, also known as Matt. List all the information about $companyName. Include a list of people and their job titles, platforms, and tools. List the number of projects, targets, and tenants. You will be penalize for including details about unrelated companies.`""

    Set-Content -Path "$obsidianPath\$companyName.md" -Value $ticketResult.StdOut
}


