$ModulePath = "$PSScriptRoot\SharedFunctions.psm1"

Import-Module $ModulePath

$jarFile = "/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar"

$arguments = Get-SplitTrimmedAndJoinedString(@"
"-Dstdout.encoding=UTF-8"
"-Dsb.slack.apidelay=300"
"-Dsb.cache.backup=true"
"-Dsb.cache.path=/home/matthew"
"-Dsb.tools.force=MultiSlackSearchCacheWarmer"
"-Dsb.exceptions.printstacktrace=false"
"-Dsb.cache.writeonly=true"
"-Dsb.multislacksearchcachewarmer.url=/home/matthew/Code/AISauron/EntityDatabase.yaml"
-jar $jarFile
"Warm the cache for Slack search"
"@)

$result = Invoke-CustomCommand java $arguments -processTimeout 0

echo $result.StdOut
echo $result.StdErr