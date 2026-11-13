import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

def jar = new File(basedir, 'target').listFiles().find { it.name.endsWith('.jar') }
assert jar
new ZipFile(jar).withCloseable { zip ->
assert zip.getEntry('prompts/events/announcement/announcement-extraction.prompt.yaml')
assert !zip.getEntry('prompt-contracts.lock.json')
}
// A successful Maven exit is insufficient if Surefire silently skipped the binding test.
def report = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
    new File(basedir, 'target/surefire-reports/TEST-example.GeneratedModelTest.xml')).documentElement
assert report.getAttribute('tests').toInteger() > 0
for (attribute in ['failures', 'errors', 'skipped']) assert report.getAttribute(attribute).toInteger() == 0
