function addOptionsToSelect(selectId, options) {
    const selectElement = document.getElementById(selectId);
    options.forEach(optionValue => {
        const option = document.createElement('option');
        option.value = optionValue;
        option.text = optionValue;
        selectElement.appendChild(option);
    });
}

const options = [
    'mistral-nemo',
    'llama3.1',
    'llama3.2',
    'llama3.3',
    'gemma2',
    'gemma2:27b',
    'gemma3',
    'gemma3:27b',
    'mixtral:8x7b',
    'mistral',
    'mistral-small',
    'phi4:14b',
    'hf.co/unsloth/phi-4-GGUF',
    'qwen2',
    'qwen2.5:32b',
    'qwen2.5-coder',
    'deepseek-r1',
    'deepseek-r1:32b'
];
addOptionsToSelect('customModel', options);