import yaml
import difflib

def load_yaml(file_path):
    with open(file_path, 'r') as file:
        return yaml.safe_load(file)

def load_text_file(file_path):
    with open(file_path, 'r') as file:
        return [line.strip().split(':') for line in file]

def similarity_ratio(a, b):
    return difflib.SequenceMatcher(None, a, b).ratio()

def main():
    yaml_file_path = '/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_5.yml'
    zendesk_file_path = '/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_12.txt'
    planhat_file_path = '/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_8.txt'

    yaml_data = load_yaml(yaml_file_path)
    zendesk_data = load_text_file(zendesk_file_path)
    planhat_data = load_text_file(planhat_file_path)

    invalid_zendesk = ([item for item in zendesk_data if len(item) != 2])
    for item in invalid_zendesk:
        print(f"Invalid line in zendesk file: {item}")
        zendesk_data.remove(item)

    invalid_planhat = ([item for item in planhat_data if len(item) != 2])
    for item in invalid_planhat:
        print(f"Invalid line in planhat file: {item}")
        planhat_data.remove(item)

    zendesk_map = {id.strip(): name.strip() for name, id in zendesk_data}
    planhat_map = {id.strip(): name.strip() for name, id in planhat_data}

    for entity in yaml_data['entities']:
        entity_name = entity['name']
        zendesk_id = entity.get('zendesk')[0] if len(entity.get('zendesk', [])) > 0 else None
        planhat_id = entity.get('planhat')[0] if len(entity.get('planhat', [])) > 0 else None

        zendesk_name = zendesk_map.get(zendesk_id, '')
        planhat_name = planhat_map.get(planhat_id, '')

        if zendesk_name and planhat_name:
            zendesk_similarity = similarity_ratio(entity_name, zendesk_name)
            planhat_similarity = similarity_ratio(entity_name, planhat_name)

            if zendesk_similarity < 0.9 or planhat_similarity < 0.9:
                print(f"Entity '{entity_name}' has low similarity:")
                print(f"  Zendesk name: '{zendesk_name}' (similarity: {zendesk_similarity:.2f})")
                print(f"  Planhat name: '{planhat_name}' (similarity: {planhat_similarity:.2f})")

if __name__ == "__main__":
    main()