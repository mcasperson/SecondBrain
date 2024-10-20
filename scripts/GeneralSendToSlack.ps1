# Replace this URL with your own Slack web hook
$uriSlack = $env:SB_SLACK_GENERAL_WEBHOOK
$body = ConvertTo-Json @{
    type = "mrkdwn"
    text = $args[0]
}

try
{
    Invoke-RestMethod -uri $uriSlack -Method Post -body $body -ContentType 'application/json'
}
catch
{
    Write-Error (Get-Date) ": Update to Slack went wrong..."
}