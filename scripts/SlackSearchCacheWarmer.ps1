$ModulePath = "$PSScriptRoot\SharedFunctions.psm1"

Import-Module $ModulePath

$arguments = Get-SplitTrimmedAndJoinedString(@"
    "-Dstdout.encoding=UTF-8"
    "-Dsb.slack.apidelay=350000"
    "-Dsb.cache.backup=true"
    "-Dsb.cache.path=/home/matthew"
    "-Dsb.tools.force=MultiSlackSearchCacheWarmer"
    "-Dsb.exceptions.printstacktrace=false"
    "-Dsb.multislacksearchcachewarmer.url=/home/matthew/Code/AISauron/EntityDatabase.yaml"
    -jar $using:jarFile
    "Warm the cache for Slack search"
"@)

$result = Invoke-CustomCommand java $arguments -processTimeout 0

echo $result.StdOut
echo $result.StdErr