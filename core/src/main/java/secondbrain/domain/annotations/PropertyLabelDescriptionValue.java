package secondbrain.domain.annotations;

public record PropertyLabelDescriptionValue(String description, String type, Object value) {
    public String getValueString() {
        if (value == null) {
            return "";
        }
        return value.toString();
    }
}
