assert new File(basedir, 'target/reports/prompt-contracts/validate/validation.txt').text.contains('[lock-baseline]')
assert new File(basedir, 'build.log').text.contains('BUILD FAILURE')
