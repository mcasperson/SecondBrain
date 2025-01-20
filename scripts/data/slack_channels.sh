CURSOR=""
TOKEN=""

while true; do
    echo "Cursor: ${CURSOR}"
    RESPONSE=$(curl -X GET -H "Authorization: Bearer ${TOKEN}" -H 'Content-type: application/json' "https://slack.com/api/conversations.list?limit=1000&cursor=${CURSOR}")

    echo $RESPONSE | jq -r '.channels[] | select(.name | startswith("account")) | .name'
    CURSOR=$(echo $RESPONSE | jq -r '.response_metadata.next_cursor')

    if [[ -z "${CURSOR}" ]]; then
        break
    fi
done