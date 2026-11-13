import javax.xml.parsers.DocumentBuilderFactory

for (module in ['first', 'second']) {
    assert new File(basedir, module + '/target/generated-sources/prompt-contracts/example/generated/AnnouncementExtractionOutput.java').isFile()
    def report = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(basedir, module + '/target/surefire-reports/TEST-example.GeneratedModelTest.xml'))
    assert report.documentElement.getAttribute('tests').toInteger() > 0 && report.documentElement.getAttribute('failures').toInteger() == 0 && report.documentElement.getAttribute('errors').toInteger() == 0
    assert report.documentElement.getAttribute('skipped').toInteger() == 0
}
assert new File(basedir, 'build.log').text.contains('MultiThreadedBuilder')
