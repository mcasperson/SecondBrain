Param (
    [switch]$GenerateCompanyReports = $true,
    [switch]$GenerateTopicReports = $true,
    [switch]$GenerateExecutiveSummary = $true,
    [switch]$GeneratePDF = $true,
    [string]$Days = "7",
    [string]$PdfTitle = "Weekly Customer Digest",
    [string]$CoverPage = "logo.jpg",
    [string]$PdfFile = $( $env:PDF_OUTPUT )
)

Function Invoke-CustomCommand
{
    Param (
        $commandPath,
        $commandArguments,
        $workingDir = (Get-Location),
        $path = @(),
        $processTimeout = 1000 * 60 * 30
    )

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

#$model = "mistral-nemo:12b-instruct-2407-q8_0"
#$model = "llama3.3"
#$model = "gemma2:27b"
#$model = "mistral-small"
$model = "qwen2.5:32b"

# Consider using K/V cache quanisation to support larger context windows with the following env vars:
# OLLAMA_KV_CACHE_TYPE="q8_0"
# OLLAMA_FLASH_ATTENTION=1

# I also found that ollama was not making full use of the GPU. This environment variable reduces the amount of
# GPU memory that is saved for the system to 500 MB, meaning more of the LLM is placed on the GPU.
# Environment="OLLAMA_GPU_OVERHEAD=524288000"

# 128K tokens can be just a bit too much when using a 70B model
#$contextWindow = "32768"
$contextWindow = "65536"
#$contextWindow = "131072"

$sevenDaysAgo = (Get-Date).AddDays(-$Days).ToString("yyyy-MM-dd")
$now = (Get-Date).ToString("yyyy-MM-dd")

# First step is to process all the entities to generate a high level summary

#$response = Invoke-WebRequest -Uri $env:sb_multislackzengoogle_url
$entitiesYaml = Get-Content -Path $env:sb_multislackzengoogle_url -Raw

# https://github.com/jborean93/PowerShell-Yayaml
$database = ConvertFrom-Yaml $entitiesYaml

$topicsYaml = Get-Content -Path $env:SB_TOPICS_YAML -Raw
$topics = ConvertFrom-Yaml $topicsYaml

if ($GenerateCompanyReports)
{

    $index = 0
    $jobs = @()
    $invokeCustomCommandFunc = ${function:Invoke-CustomCommand}.ToString()
    foreach ($entity in $database.entities)
    {
        $index++

        if ($entity.disabled)
        {
            continue
        }

        $jobs += Start-ThreadJob -ThrottleLimit 10 -ScriptBlock {

            ${function:Invoke-CustomCommand} = $invokeCustomCommandFunc

            $entityName = $using:entity.name

            $EntityLog = "/tmp/pdfgenerate $entityName $( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ).log"

            echo "Processing $entityName in $subDir $( $using:index + 1 ) of $( $using:database.entities.Count )"

            $result = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" `"-Dsb.cache.path=/home/matthew`" `"-Dsb.tools.force=MultiSlackZenGoogle`" `"-Dsb.slackzengoogle.minTimeBasedContext=1`" `"-Dsb.ollama.contextwindow=$contextWindow`" `"-Dsb.exceptions.printstacktrace=false`" `"-Dsb.multislackzengoogle.days=$Days`" `"-Dsb.multislackzengoogle.entity=$entityName`" `"-Dsb.ollama.toolmodel=$toolModel`" `"-Dsb.ollama.model=$model`" -jar $jarFile `"Write a business report based on the the last $days days worth of slack messages, ZenDesk tickets, and PlanHat activities associated with $entityName. Include an executive summary as the first paragraph. If a Google Document is supplied, it must only be used to add supporting context to the contents of the ZenDesk tickets, PlanHat activities, and Slack messaes. You will be penalized for referecing Slack Messages, ZenDesk tickets, PlanHat activities, or Google Documents that were not supplied in the prompt. You will be penalized for including a general summary of the Google Document in the report. You will be penalized for mentioning that there is no Google Document, slack messages, ZenDesk tickets, or PlanHat activities. You will be penalized for saying that you will monitor for tickets or messages in future. You will be penalized for for metioning a date range or period covered. You will be penalized for providing statistics or counts of the ZenDesk tickets. You will be penalized for providing instructions to refer to or link to the Google Document. You will be penalized for providing next steps, action items, recommendations, or looking ahead. You will be penalized for attempting to resolve the ZenDesk tickets. You will be penalized for mentioning the duration covered. You will be penalized for referencing ZenDesk tickets or PlanHat actions by ID. You must use bullet point lists instead of numbered lists. You will be penalized for using nested bullet points. You will be penalized for using numbered lists in the output.`""

            echo "Slack StdOut"
            echo $result.StdOut

            #echo "Slack StdErr"
            #echo $result.StdErr

            Add-Content -Path $EntityLog -Value "$( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ) Entity: $entityName`n"
            if ($result.ExitCode -ne 0)
            {
                Add-Content -Path $EntityLog -Value "Failed to process $entityName"
            }
            Add-Content -Path $EntityLog -Value $result.StdOut
            Add-Content -Path $EntityLog -Value $result.StdErr

            if (-not [string]::IsNullOrWhitespace($result.StdOut) -and -not $result.StdOut.Contains("InsufficientContext") -and -not $result.StdOut.Contains("Failed to call Ollama"))
            {
                Set-Content -Path "$subDir/COMPANY $entityName.md"  -Value $result.StdOut
            }
        }
    }

    Wait-Job -Job $jobs
    foreach ($job in $jobs)
    {
        Receive-Job -Job $job
    }
}

if ($GenerateTopicReports)
{
    foreach ($topic in $topics.topics)
    {

        $TopicLog = "/tmp/pdfgenerate $( $topic.name ) $( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ).log"

        # We need to be tight with the sb.slackzengoogle.keywordwindow value, as the default of 2000
        # characters was leading to whole google documents being included in the output, which was
        # consuming all the context window.
        $result = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" `"-Dsb.slackzengoogle.keywordwindow=350`" `"-Dsb.slackzengoogle.contextFilterQuestion=$( $topic.filterQuestion )`" `"-Dsb.slackzengoogle.contextFilterMinimumRating=$( $topic.filterThreshold )`" `"-Dsb.cache.path=/home/matthew`" `"-Dsb.slackzengoogle.disablelinks=false`" `"-Dsb.tools.force=MultiSlackZenGoogle`" `"-Dsb.slackzengoogle.keywords=$( $topic.keywords -join "," )`" `"-Dsb.slackzengoogle.minTimeBasedContext=1`" `"-Dsb.ollama.contextwindow=$contextWindow`" `"-Dsb.exceptions.printstacktrace=false`" `"-Dsb.multislackzengoogle.days=$Days`" `"-Dsb.ollama.toolmodel=$toolModel`" `"-Dsb.ollama.model=$model`" -jar $jarFile `"$( $topic.prompt )`n$( $topics.shared.endPrompt )`"" -processTimeout (1000 * 60 * 90)

        echo $result.StdOut
        echo $result.StdErr

        Add-Content -Path $TopicLog -Value "$( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ) Topic: $( $topic.name )`n"
        if ($result.ExitCode -ne 0)
        {
            Add-Content -Path $TopicLog -Value "Failed to process topic $( $topic.name )"
        }
        Add-Content -Path $TopicLog -Value $result.StdOut
        Add-Content -Path $TopicLog -Value $result.StdErr

        if (-not [string]::IsNullOrWhitespace($result.StdOut) -and -not $result.StdOut.Contains("InsufficientContext") -and -not $result.StdOut.Contains("Failed to call Ollama"))
        {
            Set-Content -Path "$subDir/TOPIC $( $topic.name ).md"  -Value $result.StdOut
        }
    }
}

# Step 2
# Generate an executive summary for the companies

# Loop over each file
if ($GenerateExecutiveSummary)
{
    # Delete the executie summary file
    Remove-Item "$subDir/Executive Summary.md"

    # Get all files in the directory
    $files = Get-ChildItem -Path $subDir

    $ExecutiveSummaryLog = "/tmp/pdfgenerate Executive Summary $( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ).log"

    foreach ($file in $files)
    {
        if (-not (Test-Path -Path $file -PathType Leaf))
        {
            continue
        }

        if (-not ($file.Name.StartsWith("COMPANY ")))
        {
            continue
        }

        Write-Host "Processing file: $( $file.Name )"

        $result = Invoke-CustomCommand java "`"-Dstdout.encoding=UTF-8`" `"-Dsb.tools.force=PublicWeb`" `"-Dsb.publicweb.disablelinks=true`" `"-Dsb.publicweb.url=$( $file.FullName )`"  `"-Dsb.ollama.contextwindow=$contextWindow`" `"-Dsb.exceptions.printstacktrace=false`" `"-Dsb.ollama.toolmodel=$toolModel`" `"-Dsb.ollama.model=$model`" -jar $jarFile `"Summarize the document as a single paragraph. Write the company name as a level 2 markdown header and then write the summary as plain text. You will be penalized for using 'Octopus Deploy' or 'Octopus' as the company name. You will be penalized for inlucding links or references. You will be penalized for outputing tokens lke '<|end|>'. You will be penalized for including number in square brackets, like [1], in the output.`""
        Add-Content -Path "$subDir/Executive Summary.md" -Value "$( $result.StdOut )`n`n"
        Add-Content -Path $ExecutiveSummaryLog -Value $result.StdOut
        Add-Content -Path $ExecutiveSummaryLog -Value $result.StdErr
    }
}

if ($GeneratePDF)
{
    $ExecutiveSummaryLog = "/tmp/pdfgenerate PDF $( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ).log"

    $pdfResult = Invoke-CustomCommand python3 "`"/home/matthew/Code/SecondBrain/scripts/publish/create_pdf.py`" --directory `"$subDir`" --pdf `"$PdfFile`" --title `"$PdfTitle`" --date_from `"$sevenDaysAgo`" --date_to `"$now`" --cover_page `"$CoverPage`""
    Add-Content -Path $ExecutiveSummaryLog -Value $pdfResult.StdOut
    Add-Content -Path $ExecutiveSummaryLog -Value $pdfResult.StdErr

    Write-Host $pdfResult.StdOut
    Write-Host $pdfResult.StdErr

    if ($pdfResult.ExitCode -ne 0)
    {
        Write-Error "Failed to create PDF"
    }
}




