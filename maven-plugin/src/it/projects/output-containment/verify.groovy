assert new File(basedir, 'target/reports/prompt-contracts/generate/validation.txt').text.contains('[generate]')
assert new File(basedir, 'build.log').text.contains('BUILD FAILURE')
