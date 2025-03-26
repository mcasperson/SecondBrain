import base64
import os
import pprint
import subprocess
import tempfile
from datetime import datetime, timedelta

import pytz
import requests
from dateutil import parser


def create_temp_directory():
    """
    Create a temporary directory and return its path.

    Returns:
        str: Path to the created temporary directory
    """
    try:
        # Create a temporary directory
        temp_dir = tempfile.mkdtemp()
        return temp_dir
    except Exception as e:
        print(f"Error creating temporary directory: {e}")
        return None


def run_external_command(command, shell=False):
    """
    Run an external command and return its output.

    Args:
        command: Command to run (list of strings or single string if shell=True)
        shell: Whether to use shell execution

    Returns:
        Tuple of (stdout, stderr, return_code)
    """
    try:
        # Run the command
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            shell=shell,
            text=True
        )

        # Get output
        stdout, stderr = process.communicate()
        return_code = process.returncode

        return stdout, stderr, return_code
    except Exception as e:
        return "", str(e), -1


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

    print(f"\nTotal companies: {len(company_to_calls)}")
    total_calls = sum(len(calls) for calls in company_to_calls.values())
    print(f"Total mapped calls: {total_calls}")

    tmp_dir = create_temp_directory()
    jar_file = '/home/matthew/Code/SecondBrain/cli/target/secondbrain-cli-1.0-SNAPSHOT.jar'

    for company, calls in company_to_calls.items():

        with open(os.path.join(tmp_dir, f"{company}.md"), 'w', encoding='utf-8') as f:
            f.write(f"#{company}")

        for call in calls["calls"]:
            stdout, stderr, exit_code = run_external_command([
                'java',
                '-Dstdout.encoding=UTF-8',
                '-Dsb.ollama.contextwindow=65536',
                '-Dsb.exceptions.printstacktrace=false',
                "-Dsb.cache.path=/home/matthew",
                "-Dsb.ollama.model=qwen2.5:32b",
                "-Dsb.tools.force=Gong",
                f"-Dsb.gong.callId={call["id"]}",
                '-jar',
                jar_file,
                'Generate technical call notes that include: which people were included in the call and their job titles; what dates were mentioned and why they are important; the names of any internal products; any business initiatives that were identified; which technologies, programming languages, and platforms are used; what cloud platforms (like AWS, Azure, or Google Cloud) are used; what build servers or Continuous Integration (CI) servers (like Jenkins, Azure Devops, Bamboo, TeamCity, GitHub Actions) are used; whether they deploy to virtual machines (VMs) or cloud platforms; which version control system (VCS) they use (like github, bitbucket, gitlab); any description of their existing build and deployment processes; what pain points or points of friction they identified; what metrics they identified; if there was any mention of scaling the business or deployment processes; any next steps that were identified'
            ])

            print(stderr)

            # Check for errors
            if exit_code != 0:
                print(f"Error processing call {calls["id"]}:")
                continue

            # Write output to file
            with open(os.path.join(tmp_dir, f"{company}.md"), 'w', encoding='utf-8') as f:
                f.write("\n\n## " + call['date'].strftime('%Y-%m-%d') + "\n\n" + stdout)

    print(f"Created temporary directory: {tmp_dir}")


if __name__ == "__main__":
    main()
