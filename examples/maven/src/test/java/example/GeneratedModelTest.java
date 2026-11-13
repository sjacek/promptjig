package example;
import example.generated.AnnouncementExtractionOutput;
import example.generated.AnnouncementExtractionPromptContract;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Real Maven compilation/binding and classpath packaging of the shared fictional public contract. */
class GeneratedModelTest {
 @Test void generatedDatesEnumsAndResourcesRoundTrip() throws Exception {
  var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
  String json = "{\"title\":\"Public event\",\"date\":\"2026-12-10\",\"location\":null,\"format\":\"ONLINE\",\"free\":true}";
  var value = mapper.readValue(json, AnnouncementExtractionOutput.class);
  assertEquals(LocalDate.of(2026,12,10),value.date());
  assertEquals("ONLINE",value.format().name());
  assertTrue(value.free());
  assertEquals(value,mapper.readValue(mapper.writeValueAsString(value),AnnouncementExtractionOutput.class));
  try(var input=getClass().getClassLoader().getResourceAsStream(AnnouncementExtractionPromptContract.RESOURCE)) {
   assertNotNull(input);
  }
 }
}
