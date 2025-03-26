import json
import pprint

from dateutil import parser


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
    # Load the JSON data from file
    with open('/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_24.json', 'r') as file:
        data = json.load(file)

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
