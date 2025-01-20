import yaml

def load_yaml(file_path):
    with open(file_path, 'r') as file:
        return yaml.safe_load(file)

def save_yaml_with_square_brackets(data, file_path):
    with open(file_path, 'w') as file:
        yaml.dump(data, file, default_flow_style=None)

def main():
    yaml_file_path = '/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_5.yml'
    output_file_path = '/home/matthew/.config/JetBrains/IntelliJIdea2024.3/scratches/scratch_5.yml'

    yaml_data = load_yaml(yaml_file_path)
    save_yaml_with_square_brackets(yaml_data, output_file_path)
    print(f"YAML file saved with arrays as square brackets to {output_file_path}")

if __name__ == "__main__":
    main()