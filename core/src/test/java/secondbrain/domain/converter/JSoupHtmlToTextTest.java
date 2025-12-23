package secondbrain.domain.converter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("NullAway")
public class JSoupHtmlToTextTest {

    @Test
    public void testGetText() {
        JSoupHtmlToText htmlToText = new JSoupHtmlToText();

        String html = "<p>This is a <strong>test</strong> string.</p>";
        String expected = "This is a test string.";
        String actual = htmlToText.getText(html);

        assertEquals(expected, actual);
    }

    @Test
    public void testComplexHtml() {
        JSoupHtmlToText htmlToText = new JSoupHtmlToText();

        String html = "<p style=\"text-align: left\"><strong>Acme Attendees: </strong>Regan, Tiffany, Mazhar, Ben, Abdul, Ramesh, Rizwan, Keith, and Eric<br><strong>Contoso Attendees: Contoso Attendees: </strong><a href=\"https://Contosodeploy.slack.com/team/U038QHJ30HW\" class=\"ph-editor-link\" target=\"_blank\">@alexandra</a> and <a href=\"https://Contosodeploy.slack.com/team/U05NJ8PBN21\" class=\"ph-editor-link\" target=\"_blank\">@zach</a></p><p style=\"text-align: left\"></p><ul class=\"ph-editor__bullet-list\"><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">Team introductions - lots of participants from Acme!</p></li><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">Overview of TAM Partnership and Silver Tier Offering</p></li><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">Goals and Initiatives</p><ul class=\"ph-editor__bullet-list\"><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">The priority is to move deployments off Manhattan to the Contoso Cloud instance by January 1.</p></li><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">Planning to introduce IaC pipelines with the addition of Contoso</p></li><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">Legacy applications (WebSphere) migration to Contoso in 1H of 2025</p></li><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">Implement gating process in Contoso - currently a mix of manual and automated approvals</p></li></ul></li><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">Discussed team capabilities - mirroring the Contoso capabilities assessment</p><ul class=\"ph-editor__bullet-list\"><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">The team believes that too many people can trigger production deployments</p></li><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">Deploying to production daily</p><ul class=\"ph-editor__bullet-list\"><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">There is no set deployment schedule</p></li></ul></li><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">Some teams operate in sprints with monthly releases, while others are more random.</p></li><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">Their current setup is pretty automated outside of manual approvals for production.</p></li><li class=\"ph-editor<p>This is a <strong>test</strong> string.</p>__list-item\"><p style=\"text-align: left\">No automation for database deployments - recently engaged with that team to discuss process changes.</p></li></ul></li><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">Roadblock: Internal issue accessing OC instance due to missing user to grant permissions</p><ul class=\"ph-editor__bullet-list\"><li class=\"ph-editor__list-item\"><p style=\"text-align: left\">It was unclear as to the exact issue—Zuora was mentioned—but the team lead reiterated that this is an internal Acme issue they're working on—not something that would be handled by Contoso.</p></li></ul></li></ul>\n";
        String expected = "Acme Attendees: Regan, Tiffany, Mazhar, Ben, Abdul, Ramesh, Rizwan, Keith, and Eric Contoso Attendees: Contoso Attendees: @alexandra and @zach Team introductions - lots of participants from Acme! Overview of TAM Partnership and Silver Tier Offering Goals and Initiatives The priority is to move deployments off Manhattan to the Contoso Cloud instance by January 1. Planning to introduce IaC pipelines with the addition of Contoso Legacy applications (WebSphere) migration to Contoso in 1H of 2025 Implement gating process in Contoso - currently a mix of manual and automated approvals Discussed team capabilities - mirroring the Contoso capabilities assessment The team believes that too many people can trigger production deployments Deploying to production daily There is no set deployment schedule Some teams operate in sprints with monthly releases, while others are more random. Their current setup is pretty automated outside of manual approvals for production. No automation for database deployments - recently engaged with that team to discuss process changes. Roadblock: Internal issue accessing OC instance due to missing user to grant permissions It was unclear as to the exact issue—Zuora was mentioned—but the team lead reiterated that this is an internal Acme issue they're working on—not something that would be handled by Contoso.";
        String actual = htmlToText.getText(html);

        assertEquals(expected, actual);
    }

    @Test
    public void testGetTextWithEmptyString() {
        JSoupHtmlToText htmlToText = new JSoupHtmlToText();

        String html = "";
        String expected = "";
        String actual = htmlToText.getText(html);

        assertEquals(expected, actual);
    }

    @Test
    public void testGetTextWithBlankString() {
        JSoupHtmlToText htmlToText = new JSoupHtmlToText();

        String html = "   ";
        String expected = "   ";
        String actual = htmlToText.getText(html);

        assertEquals(expected, actual);
    }

    @Test
    public void testGetTextWithNull() {
        JSoupHtmlToText htmlToText = new JSoupHtmlToText();

        String html = null;
        String expected = "";
        String actual = htmlToText.getText(html);

        assertEquals(expected, actual);
    }
}