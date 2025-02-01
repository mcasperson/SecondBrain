import os
import sys
import tiktoken

def count_tokens_in_file(file_path):
    with open(file_path, 'r', encoding='utf-8') as file:
        content = file.read()
    encoding = tiktoken.get_encoding("cl100k_base")
    tokens = encoding.encode(content)
    return len(tokens)

def count_tokens_in_directory(directory_path):
    total_tokens = 0
    for root, _, files in os.walk(directory_path):
        for file in files:
            file_path = os.path.join(root, file)
            if os.path.isfile(file_path):
                tokens = count_tokens_in_file(file_path)
                print(f"{file}: {tokens} tokens")
                total_tokens += tokens
    print(f"Total tokens in directory: {total_tokens}")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python count-tokens.py <directory_path>")
        sys.exit(1)

    directory_path = sys.argv[1]
    count_tokens_in_directory(directory_path)