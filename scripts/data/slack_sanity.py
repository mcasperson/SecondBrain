import yaml

# Load YAML file
with open('/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_5.yml', 'r') as yaml_file:
    yaml_data = yaml.safe_load(yaml_file)

# Extract slack names from YAML
yaml_slack_names = {item['slack'][0] for item in yaml_data['entities'] if len(item.get('slack', [])) > 0}

# Load text file with slack names
with open('/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_2.txt', 'r') as text_file:
    text_slack_names = {line.strip() for line in text_file}

# Find slack names in YAML but not in text file
yaml_not_in_text = yaml_slack_names - text_slack_names
print("Slack names in YAML but not in text file:")
for name in yaml_not_in_text:
    print(name)

# Find slack names in text file but not in YAML
text_not_in_yaml = text_slack_names - yaml_slack_names
print("\nSlack names in text file but not in YAML:")
for name in text_not_in_yaml:
    print(name)