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
    $processTimeout = 1000 * 60 * 120
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

    if ($processTimeout -le 0)
    {
        $p.Kill($true)
        $output = ""
        $exitCode = -1
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

# Powershell has to be set to parse the output of an executable as UTF8
# Java will print to std out as UTF 8 by passing -Dstdout.encoding=UTF-8
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8

#$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"
$jarFile = "/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar"

# Create a temporary file
$tempFile = New-TemporaryFile

# Get the directory of the temporary file
$tempDir = Split-Path -Parent $tempFile.FullName

# Remove the temporary file
Remove-Item $tempFile.FullName

$subDir = $tempDir + "/" + $( New-Guid )

mkdir $subDir

Write-Host "Working in $subDir"

$toolModel = "llama3.1"
$model = "phi4"

# Consider using K/V cache quanisation to support larger context windows with the following env vars:
# OLLAMA_KV_CACHE_TYPE="q8_0"
# OLLAMA_FLASH_ATTENTION=1
$contextWindow = "32768"
$days = "30"

$response = Invoke-WebRequest -Uri $env:sb_multislackzengoogle_url

# https://github.com/jborean93/PowerShell-Yayaml
$database = ConvertFrom-Yaml $response.Content

foreach ($entity in $database.entities)
{
    if ($entity.disabled)
    {
        continue
    }

    $entityName = $entity.name

    echo "Processing $entityName"

    $result = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" `"-Dsb.tools.force=MultiSlackZenGoogle`" `"-Dsb.slackzengoogle.minTimeBasedContext=4`" `"-Dsb.ollama.contextwindow=$contextWindow`" `"-Dsb.exceptions.printstacktrace=true`" `"-Dsb.multislackzengoogle.days=$days`" `"-Dsb.multislackzengoogle.entity=$entityName`" `"-Dsb.ollama.toolmodel=$toolModel`" `"-Dsb.ollama.model=$model`" -jar $jarFile `"Write a business report based on the the last $days days worth of slack messages, ZenDesk tickets, and PlanHat activities associated with $entityName. Include an executive summary as the first paragraph. If a Google Document is supplied, it must only be used to add supporting context to the contents of the ZenDesk tickets, PlanHat activities, and Slack messaes. You will be penalized for referecing Slack Messages, ZenDesk tickets, PlanHat activities, or Google Documents that were not supplied in the prompt. You will be penalized for including a general summary of the Google Document in the report. You will be penalized for mentioning that there is no Google Document, slack messages, ZenDesk tickets, or PlanHat activities. You will be penalized for saying that you will monitor for tickets or messages in future. You will be penalized for for metioning a date range or period covered. You will be penalized for providing statistics or counts of the ZenDesk tickets. You will be penalized for providing instructions to refer to or link to the Google Document. You will be penalized for providing next steps, action items, recommendations, or looking ahead. You will be penalized for attempting to resolve the ZenDesk tickets. You will be penalized for mentioning the duration covered. You will be penalized for referencing ZenDesk tickets or PlanHat actions by ID.`""

    echo "Slack StdOut"
    echo $result.StdOut

    #echo "Slack StdErr"
    #echo $result.StdErr

    Add-Content -Path /tmp/pdfgenerate.log -Value $result.StdOut

    if (-not [string]::IsNullOrWhitespace($result.StdOut) -and -not $result.StdOut.Contains("EmptyContext") -and -not $result.StdOut.Contains("Failed to call Ollama"))
    {
        Set-Content -Path "$subDir/$entityName.md"  -Value $result.StdOut
    }

    # Rate limits for services like Slack need to be respected
    Start-Sleep -Seconds 60
}

$result = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" `"-Dsb.tools.force=DirectoryScan`" `"-Dsb.directoryscan.individualdocumentprompt=Summarize the document as a single paragraph`" `"-Dsb.directoryscan.exclude=Executive Summary.md`" `"-Dsb.ollama.contextwindow=$contextWindow`" `"-Dsb.exceptions.printstacktrace=true`" `"-Dsb.directoryscan.directory=$subDir`" `"-Dsb.ollama.toolmodel=$toolModel`" `"-Dsb.ollama.model=$model`" -jar $jarFile `"Summarize each of the supplied File Contents under a header including the company name and a paragraph with a summary of the company's activities.`""
Set-Content -Path "$subDir/Executive Summary.md"  -Value $result.StdOut
Add-Content -Path /tmp/pdfgenerate.log -Value $result.StdOut

$pdfResult = Invoke-CustomCommand python3 "`"/home/matthew/Code/SecondBrain/scripts/publish/create_pdf.py`" `"$subDir`" `"$( $env:PDF_OUTPUT )`""
Add-Content -Path /tmp/pdfgenerate.log -Value $result.StdOut

Write-Host $pdfResult.StdOut
Write-Host $pdfResult.StdErr

if ($pdfResult.ExitCode -ne 0)
{
    Write-Error "Failed to create PDF"
}




