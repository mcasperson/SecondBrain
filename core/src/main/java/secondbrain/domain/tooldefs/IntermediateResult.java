package secondbrain.domain.tooldefs;

/**
 * IntermediateResult represents the intermediate results of a tool execution. The values captured by this record
 * are typically saved to files when the tool is executed on the command line.
 *
 * @param content  The intermediate content
 * @param filename The filename where the content is saved
 */
public record IntermediateResult(String content, String filename) {
    public IntermediateResult updateContent(final String newContent) {
        return new IntermediateResult(newContent, filename);
    }
}
