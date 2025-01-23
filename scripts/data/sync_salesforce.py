# This script synchronizes the salesforce IDs from Planhat to the YAML file.
# Generate the mapping file like this
# curl -H "Authorization: Bearer <token>" https://api-us4.planhat.com/companies?limit=10000 > response.json
# cat response.json | jq '[.[] | {name: .name, id: ._id, salesforce: .externalId}]' > mapping.json

import yaml
import json

# Load the YAML file
yaml_file_path = "/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_17.yml"
with open(yaml_file_path, 'r') as yaml_file:
    yaml_data = yaml.safe_load(yaml_file)

# Load the JSON file
json_file_path = "/home/matthew/Dropbox/mapping.json"
with open(json_file_path, 'r') as json_file:
    json_data = json.load(json_file)

# Loop through each item in the JSON array
for json_entity in json_data:
    json_id = json_entity['id']
    json_salesforce = json_entity['salesforce']

    # Loop through each entity in the YAML file
    for yaml_entity in yaml_data.get('entities', []):
        if json_id in yaml_entity.get('planhat', []):
            # Check if the salesforce item is already in the YAML entity's salesforce array
            if json_salesforce not in yaml_entity.get('salesforce', []):
                # Add the salesforce item to the YAML entity's salesforce array
                if 'salesforce' not in yaml_entity:
                    yaml_entity['salesforce'] = []
                yaml_entity['salesforce'].append(json_salesforce)

# Save the updated YAML file
with open(yaml_file_path, 'w') as yaml_file:
    yaml.dump(yaml_data, yaml_file, default_flow_style=None)

print("YAML file updated successfully.")