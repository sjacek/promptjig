assert new File(basedir, 'target/reports/prompt-contracts/lock/validation.txt').text.contains('[lock]')
assert new File(basedir, 'build.log').text.contains('BUILD FAILURE')
