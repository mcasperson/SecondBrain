#!/bin/bash

echo "" > /home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_12.txt

CURSOR=""
TOKEN=""

while true; do
    RESPONSE=$(curl --silent -H "Authorization: Basic ${TOKEN}" "https://octopus.zendesk.com/api/v2/organizations?page%5Bsize%5D=100&page%5Bafter%5D=${CURSOR}")

    echo $RESPONSE | jq -r '.organizations[] | "\(.name):\(.id)"' >> /home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_12.txt
    CURSOR=$(echo $RESPONSE | jq -r '.meta.after_cursor')
    MORE=$(echo $RESPONSE | jq -r '.meta.has_more')

    echo ${RESPONSE}
    echo ${MORE}
    echo ${CURSOR}

    if [[ "${MORE}" != "true" ]]; then
        break
    fi
done