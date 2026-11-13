assert new File(basedir, 'target/reports/prompt-contracts/validate/validation.json').text.contains('\"valid\" : true')
assert !new File(basedir,'target/generated-sources/prompt-contracts').exists()
