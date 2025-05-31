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

$ProgressPreference = 'SilentlyContinue'

Import-Module $ModulePath

# The PDF file is often generated with a date in the name.
# Bash generates a date with a command like: date +'%Y-%m-%d'
# Perecent signs are special characters in a crontab file, and need to be esacped.
# However, I found myself copying and pasting the crontab line as a way of quickly testing PDF generation.
# So, we remove an back slashes from the PDF file name to make it easier to copy and paste.
if (-not $IsWindows)
{
    $PdfFile = $PdfFile -replace '\\', ''
}

Write-Host "Generating $PdfFile"

# Powershell has to be set to parse the output of an executable as UTF8
# Java will print to std out as UTF 8 by passing -Dstdout.encoding=UTF-8
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8

#$jarFile = "C:\Apps\secondbrain-cli-1.0-SNAPSHOT.jar"
$jarFile = "/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar"

$subDir = New-TempDir

Write-Host "Working in $subDir"

$toolModel = "llama3.1"

$throttleLimit = 20

#$model = "mistral-nemo:12b-instruct-2407-q8_0"
#$model = "llama3.3"
#$model = "gemma2:27b"
#$model = "mistral-small"
#$model = "gemma3:27b"
#$model = "gemma3:12b"
#$model = "gemma3:27b-it-qat"
#$model = "qwen2.5:32b"
#$model = "qwen2.5:14b"
#$model = "qwen3:32b"
$model = "qwen3:30b-a3b"
#$model = "qwen3:14b"

#$contextWindow = "32768"
$contextWindow = "40000"
#$contextWindow = "655361"
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
        $jobs += Start-ThreadJob -StreamingHost $Host -ThrottleLimit $throttleLimit -ScriptBlock {

            Import-Module $using:ModulePath

            $entityName = ($using:entity).name

            # This is a quick way to test a single customer
#                        if ($entityName -ne "Transurban")
#                        {
#                            return
#                        }

            # Ignore the NPS entity, as it is not a customer.
            if ($entityName -eq "NPS")
            {
                return
            }

            # Delay subsequent topics by 1 min to allow the first run to pupulate the cache.
            # The API access to external systems are often configured to return all the available results across the
            # specified time range, cache them, and then have each entity pick their own data from the cache. This
            # means the first time SecondBrain is run, it will make large requests to the external systems.
            # Subsequent runs will get their data from the shared cache.
            if (($using:index) -gt 1)
            {
                Start-Sleep -m (1000 * 60)
            }

            $EntityLog = "/tmp/pdfgenerate $entityName $( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ).log"

            Get-Module
            Write-Host "Processing $entityName in $using:subDir $( $using:index ) of $( ($using:database).entities.Count )"

            $arguments = Get-SplitTrimmedAndJoinedString(@"
            "-Dh2.maxCompactTime=60000"
            "-Dstdout.encoding=UTF-8"
            "-Dsb.slack.apidelay=120000"
            "-Dsb.ollama.contextwindow=$using:contextWindow"
            "-Dsb.exceptions.printstacktrace=false"
            "-Dsb.planhat.custom1=ARR (SFDC)"
            "-Dsb.planhat.custom2=ARR Amount"
            "-Dsb.planhat.usagename1=Deployments in the last 30 days"
            "-Dsb.planhat.usageid1=676130db81b2485640f92c2e"
            "-Dsb.planhat.usagename2=Total Projects"
            "-Dsb.planhat.usageid2=6761328bff4d156e143557ac"
            "-Dsb.planhat.usagename3=Total Tenants"
            "-Dsb.planhat.usageid3=6761330999fcec6bf08648b8"
            "-Dsb.zendesk.accesstoken2=$env:SB_ZENDESK_ACCESSTOKEN_CODEFRESH"
            "-Dsb.zendesk.user2=$env:SB_ZENDESK_USER_CODEFRESH"
            "-Dsb.zendesk.url2=$env:SB_ZENDESK_URL_CODEFREH"
            "-Dsb.cache.path=/home/matthew"
            "-Dsb.tools.force=MultiSlackZenGoogle"
            "-Dsb.multislackzengoogle.minTimeBasedContext=1"
            "-Dsb.multislackzengoogle.metareport=$( $using:subDir )/COMPANY $entityName.json"
            "-Dsb.multislackzengoogle.days=$using:Days"
            "-Dsb.multislackzengoogle.entity=$entityName"
            "-Dsb.multislackzengoogle.metaPrompt1=How positive is the sentiment of the messages?"
            "-Dsb.multislackzengoogle.metaField1=Sentiment"
            "-Dsb.multislackzengoogle.metaPrompt2=Do the messages mention Amazon Cloud Services (AWS)?"
            "-Dsb.multislackzengoogle.metaField2=AWS"
            "-Dsb.multislackzengoogle.metaPrompt3=Do the messages mention Azure?"
            "-Dsb.multislackzengoogle.metaField3=Azure"
            "-Dsb.multislackzengoogle.metaPrompt4=Do the messages mention renewals, costs, pricing, invoices, payments, budgets, or licenses?"
            "-Dsb.multislackzengoogle.metaField4=Costs"
            "-Dsb.multislackzengoogle.metaPrompt5=Do the messages mention Kubernetes or any related technologies?"
            "-Dsb.multislackzengoogle.metaField5=Kubernetes"
            "-Dsb.multislackzengoogle.metaPrompt6=Do the messages mention Github?"
            "-Dsb.multislackzengoogle.metaField6=Github"
            "-Dsb.multislackzengoogle.metaPrompt7=Do the messages mention migrations or upgrades?"
            "-Dsb.multislackzengoogle.metaField7=Migration"
            "-Dsb.multislackzengoogle.metaPrompt8=Do the messages mention Terraform?"
            "-Dsb.multislackzengoogle.metaField8=Terraform"
            "-Dsb.multislackzengoogle.metaPrompt9=Do the messages mention performance?"
            "-Dsb.multislackzengoogle.metaField9=Performance"
            "-Dsb.multislackzengoogle.metaPrompt10=Do the messages mention security or compliance?"
            "-Dsb.multislackzengoogle.metaField10=Security"
            "-Dsb.multislackzengoogle.metaPrompt11=Do the messages mention Linux?"
            "-Dsb.multislackzengoogle.metaField11=Linux"
            "-Dsb.multislackzengoogle.metaPrompt12=Do the messages mention Windows?"
            "-Dsb.multislackzengoogle.metaField12=Windows"
            "-Dsb.multislackzengoogle.metaPrompt13=Do the messages mention the use of Octopus Tenants? You must only report on mentions of the Tenats feature as it is used in Octopus. You will be penalized for reporting general metions of tenants."
            "-Dsb.multislackzengoogle.metaField13=Tenants"
            "-Dsb.multislackzengoogle.metaPrompt14=Do the messages mention the use of ArgoCD?"
            "-Dsb.multislackzengoogle.metaField14=ArgoCD"
            "-Dsb.ollama.toolmodel=$using:toolModel"
            "-Dsb.ollama.model=$using:model"
            -jar $using:jarFile
            "The following instructions define the structure of the output.

            Write a business report based on the the last $using:days days worth of slack messages, ZenDesk tickets, PlanHat activities, and Gong calls associated with $entityName.

            The first paragraph must list the people who were involved in the engagement and the list of topics that were discussed.
            An example of the first paragraph is: 'In the past $using:days day we talked with with PERSON 1 (JOB TITLE 1), PERSON 2 (JOB TITLE 2), and PERSON 3 to discus TOPIC1 and TOPIC2'.

            Use a markdown level 2 subheading for each topic, and then provide the following details as a bullet point list:
            A summary of the topic;
            Who was involved in the topic, for example, 'This discussion involved PERSON 1, PERSON 2, and PERSON 3';
            Any details on why the topic is important (phrased as 'This topic is important because REASON 1, REASON 2, and REASON 3');
            Any action items, questions, or pain points associated with the topic (phrased as 'The customer asked QUESTION 1, QUESTION 2, and QUESTION 3', or 'The customer raised PAIN POINT 1, PAIN POINT 2, and PAIN POINT 3', or 'We agreed to do ACTION 1, ACTION 2, and ACTION 3');
            Any dates associated with the topic (phrased as 'The dates mentioned were DATE (DETAILS OF DATE 1), DATE (DETAILS OF DATE 2), and DATE (DETAILS OF DATE 3)');
            Any next steps associated with the topic (phrased as 'The next steps are NEXT STEP 1, NEXT STEP 2, and NEXT STEP 3');

            Use a markdown level 2 subheading for the 'Company Details' section, and then provide the following details as a bullet point list:
            The total deployments in the last 30 days;
            The total projects;
            The total tenants;
            The company's Annual Recuring Revenue (ARR).

            The following instructions provide additional details on how to write the report.

            Each of the bullet points associated with a topic must be one or two sentences long.
            If the job titles of the people involved in the topic were not supplied, you must not include any details about job titles.
            If it is not possible to determine why a topic was important, you must not include any details about why the topic was important.
            If there are no dates associated with the topic, you must not include any details about dates for that topic.
            If there are no next steps associated with the topic, you must not include any details about next steps for that topic.
            If there are no action items associated with the topic, you must not include any details about action items for that topic.
            If there are no questions associated with the topic, you must not include any details about questions for that topic.
            If there are no pain points associated with the topic, you must not include any details about pain points for that topic.

            If the ARR is not available, show the value as 'N/A'.
            If a Google Document is supplied, it must only be used to add supporting context to the contents of the ZenDesk tickets, PlanHat activities, and Slack messaes.
            You must use asterisks for bullet point lists.
            You must use bullet point lists instead of numbered lists.

            You will be penalized for including an ARR value if it was not supplied.
            You will be penalized for including job titles that were not supplied.
            You will be penalized for talking about 'limited engagement' or 'limited communication'.
            You will be penalized for using dashes for bullet point lists.
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
            You will be penalized for using nested bullet points.
            You will be penalized for using numbered lists in the output."
"@)
            $result = Invoke-CustomCommand java $arguments

            Write-Host "Final StdOut"
            Write-Host $result.StdOut

            #Write-Host "Final StdErr"
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

        $topicJobs += Start-ThreadJob -StreamingHost $Host -ThrottleLimit $throttleLimit -ScriptBlock {

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
            "-Dsb.multislackzengoogle.entity=$( ($using:topic).entityName )"
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
    if (Test-Path "$subDir/Combined Executive Summaries.md")
    {
        Remove-Item "$subDir/Combined Executive Summaries.md" | Out-Null
    }

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

        $company = $file.BaseName -replace "^COMPANY "

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
        "Write a summary of the document containing interactions with $company.
        You must use bullet points.
        The first bullet point must include the ARR (Annual Recurring Revenue) of the company. Report the ARR as 'N/A' if it is not available.
        An example of the first bullet point is: '$company has an ARR of $xxx' or '$company has an ARR of N/A'.
        The second bullet point must list the people in the company that we met with and what was discussed.
        An example of the second bullet point is: 'We talked with with PERSON 1, PERSON 2, and PERSON 3 to discuss TOPIC 1, TOPIC 2, and TOPIC 3'.
        The next bullet points must be a one sentence summary of each of the topics.
        The next bullet points must list any next steps or action items that were agreed upon. The action items must include dates if they were discussed.
        An example of the next bullet points are: 'We agreed to do ACTION 1 on DATE 1', 'We agreeded to do ACTION 2 on DATE 2', and 'We agreed to do ACTION 3'.
        The final bullet point must provide a one sentence summary of the document.

        If there are no next steps or action items, you must not include any details about next steps or action items.
        If there are no dates associated with the next steps or action items, you must not include any details about dates for those next steps or action items.
        You will be penalized for mentioning dates if they were not supplied.
        You will be penalized for mentioning a lack of Gong, Slack, ZenDesk, or PlanHat activity.
        You will be penalized for mentioning a lack of Google Document.
        You will be penalized for mentioning "the document".
        You will be penalized for including an ARR if it was not supplied.
        You will be penalized for including the total deployments in the last 30 days, total projects, or total tenants if they were not supplied.
        You will be penalized for mentioning "executive summary" or "executive summaries".
        You will be penalized for using 'Octopus Deploy', 'Octopus', 'OCTOPUS', 'OCTOPUS DEPLOY PTY LTD', 'OD', or 'Company Name' as the company name.
        You will be penalized for including a 'End of Summary' heading.
        You will be penalized for talking about "limited engagement" or "limited communication".
        You will be penalized for inlucding links or references.
        You will be penalized for outputing tokens lke '<|end|>'.
        You will be penalized for including number in square brackets, like [1], in the output.
        You will be penalized for including details about how you have adhered to the instructions."
"@)
        $result = Invoke-CustomCommand java $arguments
        Add-Content -Path "$subDir/$($file.BaseName.Replace("COMPANY", "EXECUTIVE SUMMARY") ).md"  -Value "$( $result.StdOut )"
        Add-Content -Path "$subDir/Combined Execuitve Summaries.md"  -Value "$( $result.StdOut )`n`n"
        Add-Content -Path $ExecutiveSummaryLog -Value $result.StdOut
        Add-Content -Path $ExecutiveSummaryLog -Value $result.StdErr
    }

    Write-Host "Generating topics"

    $arguments = Get-SplitTrimmedAndJoinedString(@"
    "-Dstdout.encoding=UTF-8"
    "-Dsb.tools.force=PublicWeb"
    "-Dsb.publicweb.disablelinks=true"
    "-Dsb.publicweb.url=$subDir/Combined Execuitve Summaries.md"
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

Compress-Archive -Path $subDir -DestinationPath "$PdfFile.source.zip" -Update

if ($GeneratePDF)
{
    $PdfGenerateLog = "/tmp/pdfgenerate PDF $( Get-Date -Format "yyyy-MM-dd HH:mm:ss" ).log"

    $arguments = Get-SplitTrimmedAndJoinedString(@"
    "/home/matthew/Code/SecondBrain/scripts/publish/create_pdf.py"
    --directory "$subDir"
    --pdf "$PdfFile"
    --title "$PdfTitle"
    --date_from "$from"
    --date_to "$now"
    --cover_page "$CoverPage"
"@)
    $pdfResult = Invoke-CustomCommand python3 $arguments
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



