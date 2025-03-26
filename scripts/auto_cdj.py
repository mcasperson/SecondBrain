import base64
import os
import pprint
from datetime import datetime, timedelta

import pytz
import requests
from dateutil import parser


def get_date_weeks_ago(weeks):
    # Get current date in UTC
    now = datetime.now(pytz.UTC)

    # Calculate date 6 weeks ago
    six_weeks_ago = now - timedelta(weeks=weeks)

    # Convert to Australia/Sydney timezone (UTC+10)
    sydney_tz = pytz.timezone('Australia/Sydney')
    six_weeks_ago_sydney = six_weeks_ago.astimezone(sydney_tz)

    # Format the date
    formatted_date = six_weeks_ago_sydney.strftime("%Y-%m-%dT%H:%M:%S+10:00")

    return formatted_date


def get_calls_from_gong(username, password, from_date, user_ids):
    """
    Make an HTTP call to the Gong API using basic authentication.

    Args:
        username: Username for basic auth
        password: Password for basic auth
        from_date: Start date for call filtering
        user_ids: List of user IDs to filter by (defaults to ["7758652272323866443"])

    Returns:
        The JSON response from the API or None if the request failed
    """
    url = "https://api.gong.io/v2/calls/extensive"

    # Prepare basic auth credentials
    auth_str = f"{username}:{password}"
    auth_bytes = auth_str.encode('ascii')
    auth_b64 = base64.b64encode(auth_bytes).decode('ascii')

    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Basic {auth_b64}"
    }

    # Prepare the request payload
    payload = {
        "filter": {
            "fromDateTime": from_date,
            "primaryUserIds": user_ids
        },
        "contentSelector": {
            "context": "Extended",
            "contextTiming": ["Now", "TimeOfCall"]
        }
    }

    try:
        response = requests.post(url, headers=headers, json=payload)
        response.raise_for_status()  # Raise exception for 4XX/5XX responses
        return response.json()
    except requests.exceptions.RequestException as e:
        print(f"Error making API call: {e}")
        print(f"Response status code: {getattr(e.response, 'status_code', 'N/A')}")
        print(f"Response content: {getattr(e.response, 'text', 'N/A')}")
        return None


def extract_company_names_and_call_ids(json_data):
    """
    Extract company names from Salesforce context and map them to call IDs.

    Args:
        json_data: The parsed JSON data containing call information

    Returns:
        Dictionary mapping company names to lists of call IDs
    """
    company_to_calls = {}

    # Process each call in the data
    for call in json_data.get("calls", []):
        call_id = call.get("metaData", {}).get("id")
        date = call.get("metaData", {}).get("started")

        # Process the context of each call
        for context_item in call.get("context", []):
            if context_item.get("system") == "Salesforce":
                # Look through objects for Account type
                for obj in context_item.get("objects", []):
                    if obj.get("objectType") == "Account":
                        # Look through fields for Name
                        for field in obj.get("fields", []):
                            if field.get("name") == "Name" and "value" in field:
                                company_name = field.get("value")

                                # Add to the mapping
                                if company_name not in company_to_calls:
                                    company_to_calls[company_name] = {"id": obj.get("objectId"), "calls": []}

                                if call_id not in company_to_calls[company_name]:
                                    company_to_calls[company_name]["calls"].append(
                                        {"id": call_id, "date": parser.parse(date)})

    for company in company_to_calls:
        company_to_calls[company]["calls"] = sorted(company_to_calls[company]["calls"], key=lambda x: x["date"],
                                                    reverse=True)

    return company_to_calls


def main():
    # Get the list of calls
    data = get_calls_from_gong(
        os.environ.get('SB_GONG_ACCESSKEY'),
        os.environ.get('SB_GONG_ACCESSSECRETKEY'),
        get_date_weeks_ago(6),
        ["7758652272323866443"])

    # Extract company names and map to call IDs
    company_to_calls = extract_company_names_and_call_ids(data)

    # Print the results
    print("Company to Call IDs mapping:")
    pprint.pprint(company_to_calls)

    # Print summary
    print("\nSummary:")
    for company, calls in company_to_calls.items():
        print(f"{company} ({calls["id"]}): {len(calls["calls"])} call(s)")

    print(f"\nTotal companies: {len(company_to_calls)}")
    total_calls = sum(len(calls) for calls in company_to_calls.values())
    print(f"Total mapped calls: {total_calls}")


if __name__ == "__main__":
    main()
