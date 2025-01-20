import yaml

def load_yaml(file_path):
    with open(file_path, 'r') as file:
        return yaml.safe_load(file)

def save_yaml(data, file_path):
    with open(file_path, 'w') as file:
        yaml.safe_dump(data, file, default_flow_style=None)

def load_text_file(file_path):
    with open(file_path, 'r') as file:
        return [line.strip().split(':') for line in file]

def main():
    yaml_file_path = '/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_5.yml'
    text_file_path = '/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_8.txt'

    yaml_data = load_yaml(yaml_file_path)
    text_data = load_text_file(text_file_path)

    invalid = ([item for item in text_data if len(item) != 2])
    for item in invalid:
        print(f"Invalid line in text file: {item}")
        text_data.remove(item)

    entity_id_map = {name.strip(): id.strip() for name, id in text_data}

    for entity in yaml_data['entities']:
        if 'zendesk' not in entity or not entity['planhat']:
            entity_name = entity['name']
            if entity_name in entity_id_map:
                entity['planhat'] = [entity_id_map[entity_name]]
                print(f"Set zendesk ID for '{entity_name}' to '{entity_id_map[entity_name]}'")

    save_yaml(yaml_data, yaml_file_path)
    print(f"YAML file saved with updated planhat IDs to {yaml_file_path}")

if __name__ == "__main__":
    main()