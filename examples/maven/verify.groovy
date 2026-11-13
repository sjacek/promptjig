import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

assert new File(basedir, 'target/generated-sources/prompt-contracts/example/generated/AnnouncementExtractionOutput.java').isFile()
// Require actual binding-test execution, including no skipped tests, before accepting the example.
def report = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
    new File(basedir, 'target/surefire-reports/TEST-example.GeneratedModelTest.xml')).documentElement
assert report.getAttribute('tests').toInteger() > 0
for (attribute in ['failures', 'errors', 'skipped']) assert report.getAttribute(attribute).toInteger() == 0
def jar = new File(basedir, 'target/public-announcement-testdata-1.0.jar')
assert jar.isFile()
new ZipFile(jar).withCloseable { zip ->
    assert zip.getEntry('prompts/events/announcement/announcement-extraction.prompt.yaml')
    assert !zip.getEntry('prompt-contracts.lock.json')
}
