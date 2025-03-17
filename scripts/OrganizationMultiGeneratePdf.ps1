Param (
    [switch]$GenerateCompanyReports = $true,
    [switch]$GenerateTopicReports = $true,
    [switch]$GenerateExecutiveSummary = $true,
    [switch]$GeneratePDF = $true,
    [string]$Days = "7",
    [string]$PdfTitle = "Weekly Customer Digest",
    [string]$CoverPage = "logo.jpg",
    [string]$PdfFile = $( $env:PDF_OUTPUT ),
    [string]$SlackImage = "https://gist.github.com/user-attachments/assets/e4d4c4a8-7255-4e01-bbe9-9c211df8d8df",
    [string]$SlackWebHook = $( $env:SLACK_PDF_WEBHOOK )
)

$ModulePath = "$PSScriptRoot\SharedFunctions.psm1"

Import-Module $ModulePath

# Powershell has to be set to parse the output of an executable as UTF8
# Java will print to std out as UTF 8 by passing -Dstdout.encoding=UTF-8
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8

#$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"
$jarFile = "/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar"

$subDir = New-TempDir

Write-Host "Working in $subDir"

$toolModel = "llama3.1"

#$model = "mistral-nemo:12b-instruct-2407-q8_0"
#$model = "llama3.3"
#$model = "gemma2:27b"
#$model = "mistral-small"
$model = "qwen2.5:32b"

# 128K tokens can be just a bit too much when using a 70B model
#$contextWindow = "32768"
$contextWindow = "65536"
#$contextWindow = "131072"

$from = (Get-Date).AddDays(-$Days).ToString("yyyy-MM-dd")
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
    foreach ($entity in $database.entities)
    {
        $index++

        if ($entity.disabled)
        {
            continue
        }

        # We can have thousands of entities to process, so we need to use threads to process them in parallel.
        $jobs += Start-ThreadJob -StreamingHost $Host -ThrottleLimit 20 -ScriptBlock {

            # Delay subsequent topics by 1 min to allow the first run to pupulate the cache.
            # The API access to external systems are often configured to return all the available results across the
            # specified time range, cache them, and then have each entity pick their own data from the cache. This
            # means the first time SecondBrain is run, it will make large requests to the external systems.
            # Subsequent runs will get their data from the shared cache.
            if (($using:index) -gt 1)
            {
                Start-Sleep -m (1000 * 60)
            }

            Import-Module $using:ModulePath

            $entityName = ($using:entity).name

            $EntityLog = "/tmp/pdfgenerate $entityName $( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ).log"

            Get-Module
            Write-Host "Processing $entityName in $using:subDir $( $using:index ) of $( ($using:database).entities.Count )"

            $arguments = Get-SplitTrimmedAndJoinedString(@"
            "-Dstdout.encoding=UTF-8"
            "-Dsb.slack.apidelay=120000"
            "-Dsb.ollama.contextwindow=$using:contextWindow"
            "-Dsb.exceptions.printstacktrace=false"
            "-Dsb.planhat.usagename1=Deployments in the last 30 days"
            "-Dsb.planhat.usageid1=676130db81b2485640f92c2e"
            "-Dsb.planhat.usagename2=Total Projects"
            "-Dsb.planhat.usageid2=6761328bff4d156e143557ac"
            "-Dsb.planhat.usagename3=Total Tenants"
            "-Dsb.planhat.usageid3=6761330999fcec6bf08648b8"
            "-Dsb.cache.backup=$( $using:index % 100 -eq 1 )"
            "-Dsb.cache.path=/home/matthew"
            "-Dsb.tools.force=MultiSlackZenGoogle"
            "-Dsb.multislackzengoogle.minTimeBasedContext=1"
            "-Dsb.multislackzengoogle.metareport=$( $using:subDir )/COMPANY $entityName.json"
            "-Dsb.multislackzengoogle.days=$using:Days"
            "-Dsb.multislackzengoogle.entity=$entityName"
            "-Dsb.ollama.toolmodel=$using:toolModel"
            "-Dsb.ollama.model=$using:model"
            -jar $using:jarFile
            "Write a business report based on the the last $using:days days worth of slack messages, ZenDesk tickets, and PlanHat activities associated with $entityName.
            Include an executive summary as the first paragraph.
            Show the total deployments in the last 30 days, total projects, and total tenants as a bullet point list at the end.
            If a Google Document is supplied, it must only be used to add supporting context to the contents of the ZenDesk tickets, PlanHat activities, and Slack messaes.
            You will be penalized for referecing Slack Messages, ZenDesk tickets, PlanHat activities, or Google Documents that were not supplied in the prompt.
            You will be penalized for including a general summary of the Google Document in the report.
            You will be penalized for mentioning that there is no Google Document, slack messages, ZenDesk tickets, or PlanHat activities.
            You will be penalized for saying that you will monitor for tickets or messages in future.
            You will be penalized for for metioning a date range or period covered.
            You will be penalized for providing statistics or counts of the ZenDesk tickets.
            You will be penalized for providing instructions to refer to or link to the Google Document.
            You will be penalized for providing next steps, action items, recommendations, or looking ahead.
            You will be penalized for attempting to resolve the ZenDesk tickets.
            You will be penalized for mentioning the duration covered.
            You will be penalized for referencing ZenDesk tickets or PlanHat actions by ID.
            You must use bullet point lists instead of numbered lists.
            You will be penalized for using nested bullet points.
            You will be penalized for using numbered lists in the output."
"@)
            $result = Invoke-CustomCommand java $arguments

            Write-Host "StdOut"
            Write-Host $result.StdOut

            #Write-Host "StdErr"
            #Write-Host $result.StdErr

            Add-Content -Path $EntityLog -Value "$( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ) Entity: $entityName $( $using:index ) of $( ($using:database).entities.Count )`n"
            if ($result.ExitCode -ne 0)
            {
                Add-Content -Path $EntityLog -Value "Failed to process $entityName"
            }
            Add-Content -Path $EntityLog -Value $result.StdOut
            Add-Content -Path $EntityLog -Value $result.StdErr

            if (-not [string]::IsNullOrWhitespace($result.StdOut) -and -not $result.StdOut.Contains("InsufficientContext") -and -not $result.StdOut.Contains("Failed to call Ollama"))
            {
                Set-Content -Path "$( $using:subDir )/COMPANY $entityName.md"  -Value $result.StdOut
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
    $topicJobs = @()
    $topicIndex = 0
    foreach ($topic in $topics.topics)
    {
        $topicIndex++

        $topicJobs += Start-ThreadJob -StreamingHost $Host -ThrottleLimit 3 -ScriptBlock {

            # Offset the start of the execution by a few random seconds to avoid
            # all the threads printing their output at the same time.
            Start-Sleep -Seconds (Get-Random -Minimum 1 -Maximum 11)

            Import-Module $using:ModulePath

            $TopicLog = "/tmp/pdfgenerate $( ($using:topic).name ) $( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ).log"

            $endPrompt = ($using:topics).shared.endPrompt
            if (($using:topic).endPrompt -ne $null)
            {
                $endPrompt = ($using:topic).endPrompt
            }

            # Let the first instance spin up, start the H2 database, and enable autoserver.
            # The others can connect after that.
            Start-Sleep -Seconds (10 * ($using:topicIndex - 1))

            # We need to be tight with the sb.multislackzengoogle.keywordwindow value, as the default of 2000
            # characters was leading to whole google documents being included in the output, which was
            # consuming all the context window.
            # We also need to set a large api delay for the Slack API to avoid rate limiting with many threads
            # running at once.
            $arguments = Get-SplitTrimmedAndJoinedString(@"
            "-Dstdout.encoding=UTF-8"
            "-Dsb.slack.apidelay=350000"
            "-Dsb.cache.backup=$( $using:topicIndex -eq 1 )"
            "-Dsb.cache.path=/home/matthew"
            "-Dsb.tools.force=MultiSlackZenGoogle"
            "-Dsb.ollama.contextwindow=$using:contextWindow"
            "-Dsb.exceptions.printstacktrace=false"
            "-Dsb.multislackzengoogle.days=$using:Days"
            "-Dsb.multislackzengoogle.keywords=$( ($using:topic).keywords -join "," )"
            "-Dsb.multislackzengoogle.minTimeBasedContext=1"
            "-Dsb.multislackzengoogle.keywordwindow=350"
            "-Dsb.multislackzengoogle.contextFilterQuestion=$( ($using:topic).filterQuestion )"
            "-Dsb.multislackzengoogle.contextFilterMinimumRating=$( ($using:topic).filterThreshold )"
            "-Dsb.multislackzengoogle.disablelinks=false"
            "-Dsb.ollama.toolmodel=$using:toolModel"
            "-Dsb.ollama.model=$using:model"
            -jar $using:jarFile
"@)

            # The line breaks on the prompts were significant, so they are appended as is rather than
            # being split, trimmed, and joined.
            $arguments += " `"$( ($using:topic).prompt )`n$endPrompt`""

            write-host $arguments

            $result = Invoke-CustomCommand java $arguments -processTimeout 0

            echo $result.StdOut
            echo $result.StdErr

            Add-Content -Path $TopicLog -Value "$( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ) Topic: $( ($using:topic).name )`n"
            if ($result.ExitCode -ne 0)
            {
                Add-Content -Path $TopicLog -Value "Failed to process topic $( ($using:topic).name )"
            }
            Add-Content -Path $TopicLog -Value $result.StdOut
            Add-Content -Path $TopicLog -Value $result.StdErr

            if (-not [string]::IsNullOrWhitespace($result.StdOut) -and -not $result.StdOut.Contains("InsufficientContext") -and -not $result.StdOut.Contains("Failed to call Ollama"))
            {
                Set-Content -Path "$using:subDir/TOPIC $( ($using:topic).name ).md"  -Value $result.StdOut
            }
        }
    }

    Wait-Job -Job $topicJobs
    foreach ($job in $topicJobs)
    {
        Receive-Job -Job $job
    }
}

# Step 2
# Generate an executive summary for the companies

# Loop over each file
if ($GenerateExecutiveSummary)
{
    # Delete the executie summary file
    Remove-Item "$subDir/Executive Summary.md" | Out-Null

    # Get all files in the directory
    $files = Get-ChildItem -Path $subDir

    $ExecutiveSummaryLog = "/tmp/pdfgenerate Executive Summary $( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ).log"

    foreach ($file in $files)
    {
        if (-not (Test-Path -Path $file -PathType Leaf))
        {
            continue
        }

        if (-not ($file.Name.StartsWith("COMPANY ") -and $file.Name.EndsWith(".md")))
        {
            continue
        }

        Write-Host "Processing file: $( $file.Name )"

        $arguments = Get-SplitTrimmedAndJoinedString(@"
        "-Dstdout.encoding=UTF-8"
        "-Dsb.tools.force=PublicWeb"
        "-Dsb.publicweb.disablelinks=true"
        "-Dsb.publicweb.url=$( $file.FullName )"
        "-Dsb.ollama.contextwindow=$contextWindow"
        "-Dsb.exceptions.printstacktrace=false"
        "-Dsb.ollama.toolmodel=$toolModel"
        "-Dsb.ollama.model=$model"
        -jar $jarFile
        "Summarize the document as a single paragraph.
        Write the company name as a level 2 markdown header and then write the summary as plain text.
        You will be penalized for using 'Octopus Deploy', 'Octopus', 'OCTOPUS DEPLOY PTY LTD', or 'Company Name' as the company name.
        You will be penalized for including a 'End of Summary' heading.
        You will be penalized for inlucding links or references.
        You will be penalized for outputing tokens lke '<|end|>'.
        You will be penalized for including number in square brackets, like [1], in the output.
        You will be penalized for including details about how you have adhered to the instructions."
"@)
        $result = Invoke-CustomCommand java $arguments
        Add-Content -Path "$subDir/Executive Summary.md" -Value "$( $result.StdOut )`n`n"
        Add-Content -Path $ExecutiveSummaryLog -Value $result.StdOut
        Add-Content -Path $ExecutiveSummaryLog -Value $result.StdErr
    }

    Write-Host "Generating topics"

    $arguments = Get-SplitTrimmedAndJoinedString(@"
    "-Dstdout.encoding=UTF-8"
    "-Dsb.tools.force=PublicWeb"
    "-Dsb.publicweb.disablelinks=true"
    "-Dsb.publicweb.url=$subDir/Executive Summary.md"
    "-Dsb.ollama.contextwindow=$contextWindow"
    "-Dsb.exceptions.printstacktrace=false"
    "-Dsb.ollama.toolmodel=$toolModel"
    "-Dsb.ollama.model=$model"
    -jar $jarFile
    "List the common non-functional requirements identified in the document as a bullet point list."
"@)
    $result = Invoke-CustomCommand java $arguments
    Add-Content -Path "$subDir/Topics.md" -Value "$( $result.StdOut )`n`n"
    Add-Content -Path $ExecutiveSummaryLog -Value $result.StdOut
    Add-Content -Path $ExecutiveSummaryLog -Value $result.StdErr
}

Compress-Archive -Path $subDir -DestinationPath "$PdfFile.source.zip"

if ($GeneratePDF)
{
    $PdfGenerateLog = "/tmp/pdfgenerate PDF $( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ).log"

    $pdfResult = Invoke-CustomCommand python3 "`"/home/matthew/Code/SecondBrain/scripts/publish/create_pdf.py`" --directory `"$subDir`" --pdf `"$PdfFile`" --title `"$PdfTitle`" --date_from `"$from`" --date_to `"$now`" --cover_page `"$CoverPage`""
    Add-Content -Path $PdfGenerateLog -Value $pdfResult.StdOut
    Add-Content -Path $PdfGenerateLog -Value $pdfResult.StdErr

    Write-Host $pdfResult.StdOut
    Write-Host $pdfResult.StdErr

    if ($pdfResult.ExitCode -ne 0)
    {
        Write-Error "Failed to create PDF"
    }
}

# Save to google drive with https://rclone.org/
rclone copy "$PdfFile" "gdrive:AI of Sauron"
rclone copy "$PdfFile.source.zip" "gdrive:AI of Sauron"

# Get the Slack webhook body
$PdfFileRelative = Split-Path -Path $PdfFile -Leaf
$JqFilter = '"{\"blocks\":[{\"type\":\"section\", \"text\": {\"type\": \"mrkdwn\", \"text\": \"<https://drive.google.com/file/d/" + .[0].ID + "/view?usp=sharing|' + $PdfTitle + " " + $from + " to " + $now + '>\"}},{\"type\":\"image\",\"title\": {\"type\": \"plain_text\", \"text\": \"AI of Sauron\"},\"image_url\": \"' + $SlackImage + '\", \"alt_text\": \"AI of Sauron\"}]}"'
$SlackBody = $( rclone lsjson "gdrive:AI of Sauron/$PdfFileRelative" | jq -r $JqFilter )

# Post to Slack
Invoke-RestMethod -Uri $SlackWebHook -Method Post -ContentType 'application/json' -Body $SlackBody



