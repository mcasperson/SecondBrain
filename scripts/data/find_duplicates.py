import yaml
from collections import Counter

def load_yaml(file_path):
    with open(file_path, 'r') as file:
        return yaml.safe_load(file)

def extract_names(entities):
    return [entity['name'] for entity in entities]

def find_duplicates(names):
    name_counts = Counter(names)
    return [name for name, count in name_counts.items() if count > 1]

def remove_duplicates(entities):
    seen = set()
    unique_entities = []
    for entity in entities:
        if entity['name'] not in seen:
            unique_entities.append(entity)
            seen.add(entity['name'])
    return unique_entities

def save_yaml(data, file_path):
    with open(file_path, 'w') as file:
        yaml.safe_dump(data, file)

if __name__ == "__main__":
    file_path = '/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_5.yml'
    data = load_yaml(file_path)

    if 'entities' in data:
        names = extract_names(data['entities'])
        duplicates = find_duplicates(names)

        if duplicates:
            print("Duplicate names found:", duplicates)
            data['entities'] = remove_duplicates(data['entities'])
            save_yaml(data, file_path)
            print("Duplicates removed and YAML file updated.")
        else:
            print("No duplicate names found.")
    else:
        print("No 'entities' key found in the YAML file.")