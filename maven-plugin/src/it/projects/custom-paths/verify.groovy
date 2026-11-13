import javax.xml.parsers.DocumentBuilderFactory

assert new File(basedir, 'target/custom/generated/example/generated/AnnouncementExtractionOutput.java').isFile()
assert new File(basedir, 'target/custom/generated/example/generated/AnnouncementExtractionPromptContract.java').text.contains('contracts/events/')
assert new File(basedir, 'target/custom/reports/generate/validation.json').isFile()
def report = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(basedir, 'target/surefire-reports/TEST-example.GeneratedModelTest.xml'))
assert report.documentElement.getAttribute('tests').toInteger() > 0 && report.documentElement.getAttribute('failures').toInteger() == 0 && report.documentElement.getAttribute('errors').toInteger() == 0
assert report.documentElement.getAttribute('skipped').toInteger() == 0
