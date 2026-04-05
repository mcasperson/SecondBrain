package secondbrain.domain.url;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UrlUtilsTest {

    @Test
    void testGetQueryString_ParamPresentWithPreview() {
        assertEquals("2025-04-01-preview",
                UrlUtils.getQueryString("https://example.org/openai/responses?api-version=2025-04-01-preview", "api-version"),
                "Expected the value of the api-version query parameter");
    }

    @Test
    void testGetQueryString_ParamPresent() {
        assertEquals("2024-02-01",
                UrlUtils.getQueryString("https://example.com/api?api-version=2024-02-01", "api-version"),
                "Expected the value of the api-version query parameter");
    }

    @Test
    void testGetQueryString_ParamAmongMultiple() {
        assertEquals("2024-02-01",
                UrlUtils.getQueryString("https://example.com/api?foo=bar&api-version=2024-02-01&baz=qux", "api-version"),
                "Expected the correct value when multiple query parameters are present");
    }

    @Test
    void testGetQueryString_ParamAbsent() {
        assertEquals("",
                UrlUtils.getQueryString("https://example.com/api?foo=bar&baz=qux", "api-version"),
                "Expected empty string when the parameter is absent");
    }

    @Test
    void testGetQueryString_NoQueryString() {
        assertEquals("",
                UrlUtils.getQueryString("https://example.com/api", "api-version"),
                "Expected empty string when the URL has no query string");
    }

    @Test
    void testGetQueryString_InvalidUrl() {
        assertEquals("",
                UrlUtils.getQueryString("not a valid url ://??", "api-version"),
                "Expected empty string for an invalid URL");
    }

    @Test
    void testGetQueryString_EmptyUrl() {
        assertEquals("",
                UrlUtils.getQueryString("", "api-version"),
                "Expected empty string for an empty URL");
    }

    @Test
    void testGetQueryString_ParamWithEmptyValue() {
        assertEquals("",
                UrlUtils.getQueryString("https://example.com/api?api-version=", "api-version"),
                "Expected empty string when the parameter value is empty");
    }

    @Test
    void testGetQueryString_DifferentParamName() {
        assertEquals("mymodel",
                UrlUtils.getQueryString("https://example.com/api?model=mymodel&api-version=2024-02-01", "model"),
                "Expected the correct value for a different parameter name");
    }

    @Test
    void testGetQueryString_PartialParamNameNotMatched() {
        assertEquals("",
                UrlUtils.getQueryString("https://example.com/api?api-version-extra=2024-02-01", "api-version"),
                "Expected empty string when the parameter name is only a prefix of an existing key");
    }
}

