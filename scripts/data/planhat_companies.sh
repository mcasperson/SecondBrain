TOKEN=""

curl --silent -H "Authorization: Bearer ${TOKEN}" https://api-us4.planhat.com/companies?limit=10000 | jq -r '.[] | "\(.name):\(._id)"' > /home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_8.txt