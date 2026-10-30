package software.amazon.smithy.intellij

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage

/**
 * A [ColorSettingsPage] to configure syntax highlighting for [Smithy](https://awslabs.github.io/smithy).
 *
 * @author Ian Caffey
 * @since 1.0
 */
class SmithyColorSettingsPage : ColorSettingsPage {
    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Braces", SmithyColorSettings.BRACES),
            AttributesDescriptor("Brackets", SmithyColorSettings.BRACKETS),
            AttributesDescriptor("Comma", SmithyColorSettings.COMMA),
            AttributesDescriptor("Control", SmithyColorSettings.CONTROL),
            AttributesDescriptor("Documentation comment", SmithyColorSettings.DOC_COMMENT),
            AttributesDescriptor("Dot", SmithyColorSettings.DOT),
            AttributesDescriptor("Invalid escape sequence", SmithyColorSettings.INVALID_ESCAPE_SEQUENCE),
            AttributesDescriptor("Key", SmithyColorSettings.KEY),
            AttributesDescriptor("Keyword", SmithyColorSettings.KEYWORD),
            AttributesDescriptor("Line comment", SmithyColorSettings.LINE_COMMENT),
            AttributesDescriptor("Number", SmithyColorSettings.NUMBER),
            AttributesDescriptor("Parenthesis", SmithyColorSettings.PARENS),
            AttributesDescriptor("Shape member", SmithyColorSettings.SHAPE_MEMBER),
            AttributesDescriptor("String", SmithyColorSettings.STRING),
            AttributesDescriptor("Tagged literal prefix", SmithyColorSettings.TAG),
            AttributesDescriptor("Trait", SmithyColorSettings.TRAIT_NAME),
            AttributesDescriptor("Valid escape sequence", SmithyColorSettings.VALID_ESCAPE_SEQUENCE)
        )
    }

    override fun getDisplayName() = "Smithy"
    override fun getIcon() = SmithyIcons.FILE
    override fun getHighlighter() = SmithySyntaxHighlighter()
    override fun getAdditionalHighlightingTagToDescriptorMap() = emptyMap<String, TextAttributesKey>()
    override fun getAttributeDescriptors() = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDemoText() = """
namespace example.weather

/// Provides weather forecasts.
@paginated(inputToken: "nextToken", outputToken: "nextToken", pageSize: "pageSize")
service Weather {
    version: "2006-03-01",
    resources: [City],
    operations: [GetCurrentTime]
}

resource City {
    identifiers: { cityId: CityId },
    read: GetCity,
    list: ListCities,
    resources: [Forecast],
}

resource Forecast {
    identifiers: { cityId: CityId },
    read: GetForecast,
}

// "pattern" is a trait.
@pattern("^[A-Za-z0-9 ]+$\{'$'}")
string CityId

@readonly
operation GetCity {
    input: GetCityInput,
    output: GetCityOutput,
    errors: [NoSuchResource]
}

@input
structure GetCityInput {
    // "cityId" provides the identifier for the resource and
    // has to be marked as required.
    @required
    cityId: CityId
}

@output
structure GetCityOutput {
    // "required" is used on output to indicate if the service
    // will always provide a value for the member.
    @required
    name: String,

    @required
    coordinates: CityCoordinates,
}

// This structure is nested within GetCityOutput.
structure CityCoordinates {
    @required
    latitude: Float,

    @required
    longitude: Float,
}

// "error" is a trait that is used to specialize
// a structure as an error.
@error("client")
structure NoSuchResource {
    @required
    resourceType: String
}

// The paginated trait indicates that the operation may
// return truncated results.
@readonly
@paginated(items: "items")
operation ListCities {
    input: ListCitiesInput,
    output: ListCitiesOutput
}

@input
structure ListCitiesInput {
    nextToken: String,
    pageSize: Integer
}

@output
structure ListCitiesOutput {
    nextToken: String,

    @required
    items: CitySummaries,
}

// CitySummaries is a list of CitySummary structures.
list CitySummaries {
    member: CitySummary
}

// CitySummary contains a reference to a City.
@references([{resource: City}])
structure CitySummary {
    @required
    cityId: CityId,

    @required
    name: String,
}

@readonly
operation GetCurrentTime {
    input: GetCurrentTimeInput,
    output: GetCurrentTimeOutput
}

@input
structure GetCurrentTimeInput {}

@output
structure GetCurrentTimeOutput {
    @required
    time: Timestamp
}

@readonly
operation GetForecast {
    input: GetForecastInput,
    output: GetForecastOutput
}

// "cityId" provides the only identifier for the resource since
// a Forecast doesn't have its own.
@input
structure GetForecastInput {
    @required
    cityId: CityId,
}

@output
structure GetForecastOutput {
    chanceOfRain: Float
}
""".trimIndent()
}