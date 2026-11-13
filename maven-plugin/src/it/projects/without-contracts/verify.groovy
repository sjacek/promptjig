assert !new File(basedir,'prompt-contracts.lock.json').exists()
assert new File(basedir,'target/reports/prompt-contracts/validate/validation.json').text.contains('\"contracts\" : 0')
